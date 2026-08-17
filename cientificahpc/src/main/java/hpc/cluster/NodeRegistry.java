package hpc.cluster;

import java.util.List;

/**
 * Keeps track of which nodes are currently available to run work.
 *
 * A "node" can be a fixed cluster machine (lab PC or VM, capable of
 * running tightly-coupled MPI jobs) or a Grid node (mobile/IoT device,
 * only capable of running independent, loosely-coupled work units).
 * The orchestrator uses this registry to decide where to send a job.
 */
public interface NodeRegistry {

    /**
     * Registers a node as available, or updates its info if it was
     * already registered.
     *
     * @param nodeInfo the node's data (id, hostname, type, status, capacity)
     */
    void registerNode(NodeInfo nodeInfo);

    /**
     * Removes a node from the registry (e.g. it disconnected or shut down).
     *
     * @param nodeId the id of the node to remove
     */
    void unregisterNode(String nodeId);

    /**
     * Lists every node currently marked as available, regardless of type.
     */
    List<NodeInfo> listAvailableNodes();

    /**
     * Lists available nodes of a specific type only.
     *
     * Used, for example, to fetch only FIXED nodes when building the
     * hostfile for an MPI job (MPI jobs cannot run on MOBILE/IOT nodes).
     */
    List<NodeInfo> listAvailableNodes(NodeType type);
}
