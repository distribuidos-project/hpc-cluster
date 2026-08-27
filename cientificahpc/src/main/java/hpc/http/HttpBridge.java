package hpc.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import hpc.jobs.JobStatus;
import hpc.rmi.AuthenticationException;
import hpc.rmi.HpcClusterService;

/**
 * HTTP/JSON front-end for HpcClusterService.
 *
 * WHY THIS EXISTS. cca-web is a Node.js server delivering a page to a
 * browser, and neither a browser nor Node can speak Java RMI: RMI is a
 * Java-specific binary protocol that serialises Java objects, so an RMI
 * client has to be a JVM. Without this bridge the cluster is unreachable
 * from the web interface no matter how the network is configured — the
 * browser cannot even get past the initial registry lookup.
 *
 * It exposes the same five operations of HpcClusterService as JSON over
 * HTTP. It adds no behaviour of its own: authentication, ownership and
 * job bookkeeping stay exactly where they were.
 *
 * WHERE IT RUNS. Started from Main, in the SAME JVM as RmiClusterServer,
 * and handed that same object. That means these calls never leave the
 * process: no RMI marshalling, and none of the RMI firewall trouble
 * (the registry port plus a second, randomly chosen port for the remote
 * object). RMI on 1099 keeps working untouched for any Java client.
 *
 * AUTHENTICATION. The browser sends the JWT that cca-soap issued:
 *
 *     Authorization: Bearer &lt;jwt&gt;
 *
 * The username is read from the token's own "sub" claim rather than
 * being sent separately — one less field the caller could get wrong.
 * That claim is read WITHOUT verifying the signature here on purpose:
 * it is then handed to the service together with the raw token, and
 * JwtAuthProvider.authenticate() verifies the signature and checks that
 * the username matches the subject. A forged "sub" therefore fails the
 * signature check; nothing is trusted before it is verified, and the
 * verification stays in the one class that owns it.
 */
public class HttpBridge {

    /** Same default the service map in LDAP already advertises. */
    public static final int DEFAULT_PORT = 8080;

    /** Overridable so a deployment can restrict it; never "*". */
    public static final String DEFAULT_ORIGIN = "http://cca-web";

    private static final String BASE = "/api/v1/trabajos";

    private final HpcClusterService service;
    private final int port;
    private final String allowedOrigin;
    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;

    public HttpBridge(HpcClusterService service, int port, String allowedOrigin) {
        this.service = service;
        this.port = port;
        this.allowedOrigin = allowedOrigin;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Sonda sin autenticacion, para cca-mon. Una sonda que exigiera
        // credenciales dejaria de servir justo cuando mas hace falta.
        server.createContext("/healthz", exchange -> {
            cors(exchange);
            if (preflight(exchange)) return;
            byte[] cuerpo = "ok\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, cuerpo.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(cuerpo);
            }
        });

        server.createContext(BASE, this::manejarTrabajos);

        // Un pool acotado: cada peticion es corta (consultar la cola en
        // memoria), no hace falta una hebra por conexion sin limite.
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("HTTP bridge ready on port " + port + " (origin allowed: " + allowedOrigin + ")");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------
    // Enrutado
    // -----------------------------------------------------------------
    private void manejarTrabajos(HttpExchange exchange) throws IOException {
        cors(exchange);
        if (preflight(exchange)) return;

        try {
            String ruta = exchange.getRequestURI().getPath();      // /api/v1/trabajos[/{id}/{accion}]
            String metodo = exchange.getRequestMethod();
            String resto = ruta.substring(BASE.length());          // "" | "/{id}/{accion}"

            if (resto.isEmpty() || resto.equals("/")) {
                if (!"POST".equals(metodo)) {
                    error(exchange, 405, "METODO_NO_PERMITIDO", "Use POST para enviar un trabajo");
                    return;
                }
                enviar(exchange);
                return;
            }

            String[] partes = resto.split("/");                    // ["", id, accion]
            if (partes.length < 3 || partes[1].isBlank()) {
                error(exchange, 404, "RUTA_INVALIDA", "Ruta no reconocida");
                return;
            }
            String id = java.net.URLDecoder.decode(partes[1], StandardCharsets.UTF_8);
            String accion = partes[2];

            switch (accion) {
                case "estado" -> estado(exchange, id);
                case "resultado" -> resultado(exchange, id);
                case "error" -> mensajeError(exchange, id);
                case "reintentar" -> reintentar(exchange, id);
                default -> error(exchange, 404, "RUTA_INVALIDA", "Accion no reconocida: " + accion);
            }

        } catch (AuthenticationException e) {
            error(exchange, 401, "CREDENCIALES_INVALIDAS", "El token no es valido para ese usuario");
        } catch (IllegalArgumentException e) {
            // RmiClusterServer lanza esto cuando el id no existe.
            error(exchange, 404, "NO_EXISTE", e.getMessage());
        } catch (IllegalStateException e) {
            // La cola lanza esto cuando la operacion no encaja con el
            // estado actual (reintentar algo que no ha fallado). Es un
            // error del que pide, no del servidor: 409, no 500.
            error(exchange, 409, "ESTADO_INVALIDO", e.getMessage());
        } catch (Exception e) {
            error(exchange, 500, "ERROR_INTERNO", String.valueOf(e.getMessage()));
        }
    }

    // -----------------------------------------------------------------
    // Operaciones
    // -----------------------------------------------------------------
    private void enviar(HttpExchange exchange) throws Exception {
        String token = token(exchange);
        if (token == null) return;
        String usuario = sujetoDe(token);
        if (usuario == null) {
            error(exchange, 401, "TOKEN_INVALIDO", "El token no trae un sujeto valido");
            return;
        }

        JsonNode cuerpo;
        try (InputStream in = exchange.getRequestBody()) {
            cuerpo = json.readTree(in);
        } catch (Exception e) {
            error(exchange, 400, "JSON_INVALIDO", "El cuerpo no es JSON valido");
            return;
        }

        String codigo = texto(cuerpo, "codeReference");
        String datos = texto(cuerpo, "dataReference");
        if (codigo == null || datos == null) {
            error(exchange, 400, "PETICION_INVALIDA",
                    "Faltan codeReference o dataReference");
            return;
        }

        String jobId = service.submitJob(usuario, token, codigo, datos);

        ObjectNode salida = json.createObjectNode();
        salida.put("jobId", jobId);
        responder(exchange, 200, salida);
    }

    private void estado(HttpExchange exchange, String id) throws Exception {
        if (token(exchange) == null) return;
        JobStatus estado = service.getStatus(id);
        ObjectNode salida = json.createObjectNode();
        // El enum tal cual (QUEUED/RUNNING/COMPLETED/FAILED), sin traducir:
        // traducir es cosa de la interfaz, no del contrato.
        salida.put("estado", estado == null ? null : estado.name());
        responder(exchange, 200, salida);
    }

    private void resultado(HttpExchange exchange, String id) throws Exception {
        if (token(exchange) == null) return;
        // null si aun no termino; el cliente solo lo pide cuando el
        // estado ya es COMPLETED.
        ObjectNode salida = json.createObjectNode();
        salida.put("resultado", service.getResult(id));
        responder(exchange, 200, salida);
    }

    private void mensajeError(HttpExchange exchange, String id) throws Exception {
        if (token(exchange) == null) return;
        ObjectNode salida = json.createObjectNode();
        salida.put("mensaje", service.getErrorMessage(id));
        responder(exchange, 200, salida);
    }

    private void reintentar(HttpExchange exchange, String id) throws Exception {
        if (!"POST".equals(exchange.getRequestMethod())) {
            error(exchange, 405, "METODO_NO_PERMITIDO", "Use POST para reintentar");
            return;
        }
        String token = token(exchange);
        if (token == null) return;
        String usuario = sujetoDe(token);
        if (usuario == null) {
            error(exchange, 401, "TOKEN_INVALIDO", "El token no trae un sujeto valido");
            return;
        }

        service.retryJob(usuario, token, id);

        ObjectNode salida = json.createObjectNode();
        salida.put("resultado", "OK");
        responder(exchange, 200, salida);
    }

    /** Campo de texto obligatorio: null si falta o viene vacio. */
    private static String texto(JsonNode nodo, String campo) {
        if (nodo == null) return null;
        JsonNode v = nodo.get(campo);
        if (v == null || !v.isTextual()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    // -----------------------------------------------------------------
    // Autenticacion
    // -----------------------------------------------------------------

    /** Devuelve el token, o null habiendo respondido ya un 401. */
    private String token(HttpExchange exchange) throws IOException {
        String cabecera = exchange.getRequestHeaders().getFirst("Authorization");
        if (cabecera == null || !cabecera.regionMatches(true, 0, "Bearer ", 0, 7)) {
            error(exchange, 401, "TOKEN_AUSENTE", "Falta la cabecera Authorization: Bearer");
            return null;
        }
        String token = cabecera.substring(7).trim();
        if (token.isEmpty()) {
            error(exchange, 401, "TOKEN_AUSENTE", "El token va vacio");
            return null;
        }
        return token;
    }

    /**
     * Lee el claim "sub" del JWT SIN verificar la firma. No es un
     * descuido: el valor se pasa despues a submitJob/retryJob junto con
     * el token, y JwtAuthProvider comprueba la firma y que el sujeto
     * coincida. Un "sub" falsificado no sobrevive esa comprobacion.
     */
    private String sujetoDe(String token) {
        try {
            String[] partes = token.split("\\.");
            if (partes.length < 2) return null;
            byte[] carga = Base64.getUrlDecoder().decode(partes[1]);
            JsonNode nodo = json.readTree(carga);
            String sub = nodo.path("sub").asText(null);
            return (sub == null || sub.isBlank()) ? null : sub;
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------
    private void cors(HttpExchange exchange) {
        Headers h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", allowedOrigin);
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    }

    /** Responde el preflight del navegador. true si ya se atendio. */
    private boolean preflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) return false;
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private void responder(HttpExchange exchange, int codigo, JsonNode cuerpo) throws IOException {
        byte[] datos = json.writeValueAsBytes(cuerpo);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(codigo, datos.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(datos);
        }
    }

    /** Mismo formato de error que cca-repo, para que la interfaz lo maneje igual. */
    private void error(HttpExchange exchange, int http, String codigo, String mensaje) throws IOException {
        ObjectNode raiz = json.createObjectNode();
        ObjectNode err = raiz.putObject("error");
        err.put("codigo", codigo);
        err.put("mensaje", mensaje == null ? "" : mensaje);
        responder(exchange, http, raiz);
    }
}
