package hpc.cluster;

import java.io.Serializable;

/**
 * Data describing a single node registered in the NodeRegistry.
 */
public class NodeInfo implements Serializable {

    private final String id;
    private final String hostname;
    private final NodeType type;
    private final NodeStatus status;
    private final int capacity; // e.g. number of CPU cores/slots available for jobs

    public NodeInfo(String id, String hostname, NodeType type, NodeStatus status, int capacity) {
        this.id = id;
        this.hostname = hostname;
        this.type = type;
        this.status = status;
        this.capacity = capacity;
    }

    public String getId() {
        return id;
    }

    public String getHostname() {
        return hostname;
    }

    public NodeType getType() {
        return type;
    }

    public NodeStatus getStatus() {
        return status;
    }

    public int getCapacity() {
        return capacity;
    }
}
