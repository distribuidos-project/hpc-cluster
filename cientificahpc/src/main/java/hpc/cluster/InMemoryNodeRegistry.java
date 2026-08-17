package hpc.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of NodeRegistry. Nodes must be registered
 * explicitly (e.g. at server startup, from a fixed list of the lab/VM
 * cluster machines) — there is no automatic discovery yet.
 */
public class InMemoryNodeRegistry implements NodeRegistry {

    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    @Override
    public void registerNode(NodeInfo nodeInfo) {
        nodes.put(nodeInfo.getId(), nodeInfo);
    }

    @Override
    public void unregisterNode(String nodeId) {
        nodes.remove(nodeId);
    }

    @Override
    public List<NodeInfo> listAvailableNodes() {
        return nodes.values().stream()
                .filter(node -> node.getStatus() == NodeStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    @Override
    public List<NodeInfo> listAvailableNodes(NodeType type) {
        return nodes.values().stream()
                .filter(node -> node.getStatus() == NodeStatus.AVAILABLE && node.getType() == type)
                .collect(Collectors.toList());
    }
}
