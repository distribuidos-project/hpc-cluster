package hpc.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real implementation of AuthProvider.
 *
 * The "credential" here is not a password: it is the RS256 JWT that
 * cca-soap hands back after it validated the user against LDAP (and TOTP,
 * if enabled) — see cca-soap's AuthService.autenticar()/TokenService.firmar().
 * This class never talks to LDAP itself; it only checks that the token is
 * genuinely signed by cca-soap's private key, by verifying the signature
 * with the matching public key (jwt-public.pem, read from
 * cientificahpc/keys/ at startup -- see DEFAULT_PUBLIC_KEY_PATH). The
 * actual "is this password correct" question
 * was already answered upstream, by the directory service — this class
 * only has to trust the witness, not repeat its work.
 *
 * Verification is delegated to jjwt (io.jsonwebtoken) rather than
 * hand-parsed: a real library handles the token's JSON payload without
 * being tied to today's exact field list (TokenService.php could add or
 * reorder claims without warning to a regex), and verifyWith(publicKey)
 * only accepts signature algorithms compatible with an RSA public key —
 * it will not fall back to an unsigned "alg: none" token, nor accept an
 * RS256-signed token re-submitted as HS256 using the public key bytes as
 * an HMAC secret. Both are classic JWT library vulnerabilities that a
 * hand-rolled verifier has to remember to defend against explicitly;
 * jjwt closes them by construction once given a real Key object.
 */
public class JwtAuthProvider implements AuthProvider {

    // Relative to the working directory Main is started from (see
    // hpc/Main.java) -- not under src/main/resources on purpose: a public
    // key isn't secret, but it also isn't source code or something this
    // repo should own a stale copy of. Each checkout/deployment drops its
    // own copy of cca-soap's current key there; it is gitignored (*.pem).
    private static final String DEFAULT_PUBLIC_KEY_PATH = "keys/jwt-public.pem";
    private static final String DEFAULT_ISSUER = "cca-soap"; // cca-soap/config/config.php: jwt_issuer

    private final PublicKey publicKey;
    private final String expectedIssuer;

    // Populated on a successful authenticate(); lets getUser(username) hand
    // back the uid/groups carried by that user's last valid token, without
    // this class needing to talk to LDAP itself. Simple on purpose: it is
    // never evicted, which is fine for the handful of test users this
    // project runs with, but would grow unbounded with a real user base.
    private final Map<String, UserInfo> lastValidated = new ConcurrentHashMap<>();

    public JwtAuthProvider() {
        this(loadPublicKeyFromFile(System.getProperty("hpc.auth.jwtPublicKeyPath", DEFAULT_PUBLIC_KEY_PATH)),
                DEFAULT_ISSUER);
    }

    public JwtAuthProvider(PublicKey publicKey, String expectedIssuer) {
        this.publicKey = publicKey;
        this.expectedIssuer = expectedIssuer;
    }

    @Override
    public boolean authenticate(String username, String credential) {
        if (username == null || username.isBlank() || credential == null || credential.isBlank()) {
            return false;
        }
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(expectedIssuer)
                    .build()
                    .parseSignedClaims(credential);
            Claims claims = jws.getPayload();

            // JobQueue/RmiClusterServer track ownership by the username
            // string, so the token presented has to actually belong to the
            // username it is submitted alongside — otherwise user A's
            // token could be replayed as if it were user B's job.
            if (!username.equals(claims.getSubject())) {
                return false;
            }

            Long uid = claims.get("uid", Long.class);
            if (uid == null) {
                return false;
            }

            lastValidated.put(username, new UserInfo(String.valueOf(uid), claims.getSubject(), extractGroupIds(claims)));
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // Covers: bad/forged signature, expired token, wrong issuer,
            // malformed JWT structure — jjwt validates exp/iss as part of
            // parseSignedClaims() itself, so none of that needs to be
            // re-checked here.
            return false;
        }
    }

    @Override
    public UserInfo getUser(String username) {
        return lastValidated.get(username);
    }

    private static List<String> extractGroupIds(Claims claims) {
        List<?> rawGids = claims.get("gids", List.class);
        List<String> groupIds = new ArrayList<>();
        if (rawGids != null) {
            for (Object gid : rawGids) {
                groupIds.add(String.valueOf(gid));
            }
        }
        return groupIds;
    }

    private static PublicKey loadPublicKeyFromFile(String path) {
        Path keyPath = Paths.get(path);
        try {
            return decodePemPublicKey(Files.readString(keyPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not read JWT public key at " + keyPath.toAbsolutePath()
                            + " -- copy cca-soap's current jwt-public.pem there, or point "
                            + "-Dhpc.auth.jwtPublicKeyPath at wherever it lives on this machine",
                    e);
        }
    }

    private static PublicKey decodePemPublicKey(String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid RSA public key in PEM content", e);
        }
    }
}
