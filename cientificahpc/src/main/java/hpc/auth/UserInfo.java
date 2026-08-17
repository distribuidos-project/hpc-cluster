package hpc.auth;

import java.io.Serializable;
import java.util.List;

/**
 * Basic user data, returned by AuthProvider.
 *
 * Implements Serializable because this object may travel across an RMI
 * call (as a parameter or return value), and RMI needs to be able to
 * serialize everything that crosses that boundary.
 */
public class UserInfo implements Serializable {

    private final String id;
    private final String username;
    private final List<String> groups;

    public UserInfo(String id, String username, List<String> groups) {
        this.id = id;
        this.username = username;
        this.groups = groups;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getGroups() {
        return groups;
    }
}
