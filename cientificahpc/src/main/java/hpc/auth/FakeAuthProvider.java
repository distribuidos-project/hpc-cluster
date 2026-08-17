package hpc.auth;

import java.util.List;
import java.util.Map;

/**
 * Fake implementation of AuthProvider, with in-memory test users.
 *
 * Used to develop and test the full flow (authentication, job submission,
 * etc.) while the real directory service does not exist yet. Once the
 * directory is ready, this class gets replaced by another one (e.g.
 * LdapAuthProvider) that implements the same AuthProvider interface,
 * without having to touch the rest of the system.
 */
public class FakeAuthProvider implements AuthProvider {

    // username -> password (plain text only because this is a test mock,
    // never do this in a real directory)
    private final Map<String, String> credentials = Map.of(
            "juan", "1234",
            "maria", "1234",
            "admin", "admin"
    );

    // username -> user data
    private final Map<String, UserInfo> users = Map.of(
            "juan", new UserInfo("u1", "juan", List.of("scientists")),
            "maria", new UserInfo("u2", "maria", List.of("scientists")),
            "admin", new UserInfo("u3", "admin", List.of("scientists", "administrators"))
    );

    @Override
    public boolean authenticate(String username, String credential) {
        String expectedPassword = credentials.get(username);
        return expectedPassword != null && expectedPassword.equals(credential);
    }

    @Override
    public UserInfo getUser(String username) {
        return users.get(username);
    }
}
