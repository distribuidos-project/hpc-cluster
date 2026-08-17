package hpc.rmi;

/**
 * Thrown by HpcClusterService.submitJob when the provided credentials
 * are not valid according to AuthProvider.
 */
public class AuthenticationException extends Exception {

    public AuthenticationException(String message) {
        super(message);
    }
}
