package hpc.auth;

/**
 * Authentication contract for the HPC/MPI component.
 *
 * Currently implemented by a fake class (FakeAuthProvider) with in-memory
 * test users. Once the real directory service is ready, a new class should
 * be created that implements this same interface (e.g. LdapAuthProvider)
 * querying the real directory. The rest of the system (RmiClusterServer,
 * JobQueue, etc.) does not need to change, since it only depends on this
 * contract, never on the concrete implementation.
 */
public interface AuthProvider {

    /**
     * Verifies whether the user's credentials are correct.
     *
     * @param username   the username
     * @param credential password (or token, depending on what is decided later)
     * @return true if the credentials are valid, false otherwise
     */
    boolean authenticate(String username, String credential);

    /**
     * Retrieves basic information about an already authenticated user.
     *
     * @param username the username
     * @return the user's data (id, username, groups), or null if it does not exist
     */
    UserInfo getUser(String username);
}
