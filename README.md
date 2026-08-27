# hpc-cluster — Clúster de cómputo MPI

Nodo del sistema distribuido UPB-CIENTÍFICA. Es el servicio de **MPI
Jobs**: recibe trabajos, compila código C, lo ejecuta en paralelo sobre
cinco máquinas y devuelve la salida.

## Qué expone, y a quién

| Interfaz | Puerto | Quién la usa |
|---|---|---|
| **Java RMI** (`HpcClusterService`) | 1099 | Clientes Java |
| **HTTP/JSON** (puente) | 8080 | El navegador, a través de `cca-web` |

Las dos hablan con el **mismo objeto** en el mismo JVM. El puente HTTP no
añade lógica propia: autenticación, propiedad de los trabajos y gestión
de la cola siguen exactamente donde estaban.

### Por qué existe el puente HTTP

`cca-web` es un servidor Node.js que entrega una interfaz a un navegador,
y **ni el navegador ni Node pueden hablar Java RMI**. No es un problema
de red ni de puertos: RMI es un protocolo binario específico de Java que
serializa objetos Java, así que un cliente RMI tiene que ser una JVM.

Sin el puente, el clúster es inalcanzable desde la web por muy bien
configurada que esté la red — el navegador no llega ni al `lookup`
inicial del registro.

**Efecto secundario útil:** como el puente corre en el mismo JVM, sus
llamadas nunca salen del proceso. Eso elimina el problema clásico de RMI
tras un cortafuegos (además del 1099, RMI abre un segundo puerto
aleatorio para el objeto remoto). Para la web solo hay que abrir el
puerto HTTP.

## Estructura

```
cientificahpc/src/main/java/hpc/
├── Main.java              arranque: cablea todo, registra nodos, levanta RMI y HTTP
├── rmi/
│   ├── HpcClusterService  el contrato remoto (5 operaciones)
│   └── RmiClusterServer   lo implementa: autentica y delega en la cola
├── http/
│   └── HttpBridge         expone esas 5 operaciones como JSON sobre HTTP
├── auth/                  JwtAuthProvider verifica el token de cca-soap
├── jobs/                  la cola y el modelo de trabajo
├── home/                  resuelve las referencias contra el Home por NFS
├── runner/                CRunner: mpicc y mpirun
├── cluster/               registro de nodos
└── worker/                JobExecutionLoop: el ciclo que ejecuta
```

`Main` es la única clase que conoce implementaciones concretas; todo lo
demás depende solo de interfaces. Cambiar una pieza (por ejemplo, a
`FakeAuthProvider` para probar sin `cca-soap`) es cambiar una línea ahí.

---

# Montaje

## Fase 0 — Red

En un entorno nuevo el DHCP reasigna direcciones. Anotar las nuevas:

```bash
ip a | grep "inet " | grep -v 127.0.0.1
```

Cambia sobre todo la del **Adaptador 1 (Puente)**; la interna puede
quedar igual.

## Fase 1 — `/etc/hosts` en los cinco nodos

Lo único que hay que tocar es la entrada de `cca-dir`, y las de los otros
cuatro nodos entre sí **si la red interna cambió**. Todo lo demás
(`sssd.conf`, llaves SSH, `/etc/exports`, `/etc/fstab`) está armado por
hostname, así que con `/etc/hosts` correcto no hay que reconfigurar nada.

```bash
sudo sed -i -E '/^[0-9.]+[[:space:]]+cca-dir[[:space:]]*$/d' /etc/hosts
echo "<IP-real-de-cca-dir>  cca-dir" | sudo tee -a /etc/hosts
```

## Fase 2 — Verificar lo ya configurado

```bash
ping -c2 cca-dir
getent passwd mpiuser          # debe seguir dando 9001

sudo -iu mpiuser
for h in hcp-master hcp-worker-node-1 hcp-worker-node-2 hcp-worker-node-3 hcp-worker-node-4; do
  ssh -o BatchMode=yes -o ConnectTimeout=5 "$h" hostname
done
exit

mountpoint -q /shared && echo "shared OK" || echo "revisar mount"
```

Si `mountpoint` falla en algún worker es porque cambió la IP interna de
`hcp-master`; con el `/etc/hosts` corregido debería resolver solo —
probar `sudo mount -a` de nuevo.

## Fase 3 — Confirmar que MPI sigue andando

```bash
mpirun --hostfile /shared/hostfile -np 5 /shared/hola
```

Si imprime los cinco hostnames, el clúster está funcional.

## Fase 4 — Requisitos del servidor en `hcp-master`

El servidor **no arranca** sin estas dos cosas: `JwtAuthProvider` y
`RepositoryHomeFetcher` fallan al construirse si faltan.

```bash
# 1. el Home por NFS (necesita cca-repo alcanzable)
sudo apt install -y nfs-common
sudo mkdir -p /srv/home
sudo mount -t nfs cca-repo:/srv/home /srv/home

# 2. la clave publica del JWT
ls /opt/hpc/keys/jwt-public.pem
```

La clave **no está en git** (`*.pem` está ignorado): cada despliegue deja
su propia copia, tomada del repositorio `contratos`.

## Fase 5 — Arrancar

```bash
cd /opt/hpc
sudo -u mpiuser java -jar hpc.jar
```

Esperado:

```
HTTP bridge ready on port 8080 (origin allowed: http://cca-web)
HPC cluster server ready on port 1099 as 'HpcClusterService'.
Registered nodes: [...]
```

### Opciones sin recompilar

| Propiedad | Para qué | Por defecto |
|---|---|---|
| `-Dhpc.http.port` | Puerto del puente HTTP | `8080` |
| `-Dhpc.http.origin` | Origen permitido (CORS) | `http://cca-web` |
| `-Dhpc.home.nfsRoot` | Raíz del Home montado | `/srv/home` |
| `-Dhpc.auth.jwtPublicKeyPath` | Clave pública | `keys/jwt-public.pem` |

**`hpc.http.origin` tiene que coincidir exactamente** con el origen desde
el que se sirve la interfaz — protocolo, host y puerto. Si no coincide,
el navegador bloquea las llamadas sin error visible en el servidor.
Durante pruebas locales suele ser `http://localhost:8080`:

```bash
sudo -u mpiuser java -Dhpc.http.origin=http://localhost:8080 -jar hpc.jar
```

---

# La API HTTP

Base: `http://cca-rmi:8080/api/v1`. Todas exigen
`Authorization: Bearer <jwt>` salvo `/healthz`.

El **usuario se lee del claim `sub` del propio token**, no se envía
aparte. Ese claim se lee sin verificar la firma, y se pasa junto al token
a `JwtAuthProvider`, que sí verifica la firma y comprueba que el sujeto
coincida — un `sub` falsificado no sobrevive esa comprobación.

| Ruta | Método | Devuelve |
|---|---|---|
| `/healthz` | GET | `ok`. Sin token |
| `/api/v1/trabajos` | POST | `{"jobId": "..."}` |
| `/api/v1/trabajos/{id}/estado` | GET | `{"estado":"QUEUED\|RUNNING\|COMPLETED\|FAILED"}` |
| `/api/v1/trabajos/{id}/resultado` | GET | `{"resultado":"..."}` o `null` si no terminó |
| `/api/v1/trabajos/{id}/error` | GET | `{"mensaje":"..."}` o `null` si no falló |
| `/api/v1/trabajos/{id}/reintentar` | POST | `{"resultado":"OK"}` |

Enviar un trabajo:

```json
{
  "codeReference": "/srv/home/jperez/estadisticas.c",
  "dataReference": "/srv/home/jperez/mediciones.csv"
}
```

Son **referencias, no contenidos**: un dataset grande nunca viaja como
parámetro. `HomeFetcher` las resuelve contra el Home del usuario.

## Errores

Mismo formato que `cca-repo`, para que la interfaz los maneje igual:

```json
{ "error": { "codigo": "NO_EXISTE", "mensaje": "Unknown job id: ..." } }
```

| HTTP | Código | Cuándo |
|---|---|---|
| 400 | `JSON_INVALIDO` / `PETICION_INVALIDA` | Cuerpo ilegible o faltan campos |
| 401 | `TOKEN_AUSENTE` / `TOKEN_INVALIDO` / `CREDENCIALES_INVALIDAS` | Sin token, mal firmado, expirado, o el sujeto no coincide |
| 404 | `NO_EXISTE` | El id de trabajo no existe |
| 405 | `METODO_NO_PERMITIDO` | Método equivocado para esa ruta |
| 409 | `ESTADO_INVALIDO` | Reintentar un trabajo que no ha fallado |
| 500 | `ERROR_INTERNO` | Cualquier otra cosa |

---

# Registrar el servicio

Para que `cca-web` encuentre el clúster sin llevar la dirección escrita
dentro, hay que registrarlo en `ou=services` del LDAP apuntando al
**puerto HTTP**, no al 1099:

```
cn=jobs, labeledURI: http://cca-rmi:8080
```

Si el puente no está corriendo, conviene **quitar esa entrada**: así la
interfaz muestra un aviso claro de que el clúster no está disponible, en
vez de intentar conectarse y fallar con un error de red.

---

# Comprobar que quedó bien

Desde el PC donde corre el navegador, **no** desde `hcp-master`:

```bash
# 1. el nombre resuelve y hay algo escuchando
curl -m 5 http://cca-rmi:8080/healthz          # -> ok

# 2. enviar un trabajo
curl -m 5 -X POST http://cca-rmi:8080/api/v1/trabajos \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"codeReference":"/srv/home/jperez/estadisticas.c","dataReference":"/srv/home/jperez/mediciones.csv"}'

# 3. LA QUE MAS SE OLVIDA: el preflight de CORS
curl -m 5 -i -X OPTIONS http://cca-rmi:8080/api/v1/trabajos \
  -H "Origin: http://localhost:8080" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type,authorization"
```

La tercera debe responder **204** con las tres cabeceras
`Access-Control-*`. El navegador la manda antes de cada POST con
`Content-Type: application/json`; si falla, bloqueará todo aunque la
segunda funcione perfectamente. `curl` normal no lo detecta porque no
aplica CORS.

## Diagnóstico rápido

| Lo que ve el navegador | Qué significa |
|---|---|
| `ERR_NAME_NOT_RESOLVED` | Falta `cca-rmi` en el `hosts` del PC |
| `ERR_CONNECTION_REFUSED` | El nombre resuelve, pero nadie escucha en ese puerto |
| `ERR_CONNECTION_TIMED_OUT` | Cortafuegos, o la VM no responde |
| Error de CORS | `hpc.http.origin` no coincide con el origen de la interfaz |
| 404 | El puente responde, pero la ruta no existe |

---

# Compilar

```bash
cd cientificahpc
mvn clean package
```

Genera `target/cientificahpc-1.0-SNAPSHOT-all.jar` con todas las
dependencias dentro (`maven-shade-plugin`).

Requiere Java 17 o superior. Las dependencias son `jjwt` (verificación
del token) y `jackson-databind` (JSON del puente); esta última ya venía
de forma transitiva por `jjwt-jackson`, así que no añade peso al jar.

El servidor HTTP es `com.sun.net.httpserver`, incluido en el JDK: no hace
falta ningún framework web.

---

# Limitaciones conocidas

**No existe una operación para listar trabajos.** Ni por RMI ni por HTTP.
Un cliente solo puede seguir los trabajos cuyo id recuerde; la interfaz
web los guarda en memoria y **los pierde al recargar la página**, aunque
sigan corriendo. Añadir un `listJobs(username, credential)` expuesto como
`GET /api/v1/trabajos` resolvería esto.

**Un reintento reempieza desde cero.** No se reanuda una ejecución MPI a
medias; es una decisión explícita del proyecto.

**Los nodos están fijos en el código.** No hay auto-registro
(`registerKnownNodes` en `Main`). Suficiente mientras el clúster sean
cinco VMs conocidas.

**Sin HTTPS.** El token viaja en claro por la red del laboratorio, igual
que en el resto del sistema. Riesgo aceptado y documentado.
