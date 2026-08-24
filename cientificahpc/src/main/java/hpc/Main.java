package hpc;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

import hpc.auth.AuthProvider;
import hpc.auth.JwtAuthProvider;
import hpc.cluster.InMemoryNodeRegistry;
import hpc.cluster.NodeInfo;
import hpc.cluster.NodeRegistry;
import hpc.cluster.NodeStatus;
import hpc.cluster.NodeType;
import hpc.home.HomeFetcher;
import hpc.home.HomePublisher;
import hpc.home.RepositoryHomeFetcher;
import hpc.home.RepositoryHomePublisher;
import hpc.jobs.InMemoryJobQueue;
import hpc.jobs.JobQueue;
import hpc.rmi.RmiClusterServer;
import hpc.runner.CRunner;
import hpc.runner.LanguageRunner;
import hpc.worker.JobExecutionLoop;

/**
 * Bootstrap entry point for the HPC cluster server. This is the ONLY class
 * in the whole project that is allowed to know about concrete
 * implementations (JwtAuthProvider, RepositoryHomeFetcher, CRunner, etc.) —
 * every other class only depends on interfaces. Swapping any piece (e.g.
 * back to FakeAuthProvider/TestHomeFetcher for a quick local test without
 * cca-soap/cca-repo reachable) means changing a single line here.
 *
 * Responsibilities:
 *   - wires every interface to its concrete implementation
 *   - registers the known, fixed cluster nodes (no self-registration yet)
 *   - starts JobExecutionLoop in its own background thread
 *   - starts the RMI registry and publishes RmiClusterServer on it
 *
 * Must run directly on the master node (hcp-master): CRunner spawns local
 * OS processes (mpicc/mpirun), so this JVM has to live where those
 * commands are actually available. RepositoryHomeFetcher/Publisher also
 * require cca-repo's NFS export (port 2049) already mounted locally
 * (default /srv/home, overridable with -Dhpc.home.nfsRoot=...) before this
 * starts — see hpc/home/NfsHomeRoot.java. JwtAuthProvider needs
 * cca-soap's current public key at keys/jwt-public.pem, relative to
 * wherever this is launched from (overridable with
 * -Dhpc.auth.jwtPublicKeyPath=...) — not committed to git, each
 * checkout/deploy drops its own copy there.
 */
public class Main {

    private static final int RMI_PORT = 1099;
    private static final String SERVICE_NAME = "HpcClusterService";

    public static void main(String[] args) throws Exception {
        AuthProvider authProvider = new JwtAuthProvider();
        HomeFetcher homeFetcher = new RepositoryHomeFetcher();
        HomePublisher homePublisher = new RepositoryHomePublisher();
        LanguageRunner languageRunner = new CRunner();
        JobQueue jobQueue = new InMemoryJobQueue();
        NodeRegistry nodeRegistry = new InMemoryNodeRegistry();

        registerKnownNodes(nodeRegistry);

        JobExecutionLoop executionLoop = new JobExecutionLoop(
                jobQueue, homeFetcher, homePublisher, languageRunner, nodeRegistry);
        Thread executionThread = new Thread(executionLoop, "job-execution-loop");
        executionThread.start();

        RmiClusterServer server = new RmiClusterServer(authProvider, jobQueue);
        Registry registry = LocateRegistry.createRegistry(RMI_PORT);
        registry.rebind(SERVICE_NAME, server);

        System.out.println("HPC cluster server ready on port " + RMI_PORT + " as '" + SERVICE_NAME + "'.");
        System.out.println("Registered nodes: " + nodeRegistry.listAvailableNodes());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down job execution loop...");
            executionLoop.stop();
        }));
    }

    /**
     * Registers the known VM cluster nodes. There is no self-registration
     * mechanism yet — that would only be needed for the Grid layer
     * (mobile/IoT devices), which is out of scope for now. Extended to 4
     * workers here to match the actual lab cluster (hcp-worker-node-1..4);
     * follow the same pattern from INSTRU_1.MD/Parte 4 when adding more.
     */
    private static void registerKnownNodes(NodeRegistry nodeRegistry) {
        nodeRegistry.registerNode(new NodeInfo("master", "hcp-master", NodeType.FIXED, NodeStatus.AVAILABLE, 1));
        nodeRegistry.registerNode(
                new NodeInfo("worker1", "hcp-worker-node-1", NodeType.FIXED, NodeStatus.AVAILABLE, 1));
        nodeRegistry.registerNode(
                new NodeInfo("worker2", "hcp-worker-node-2", NodeType.FIXED, NodeStatus.AVAILABLE, 1));
        nodeRegistry.registerNode(
                new NodeInfo("worker3", "hcp-worker-node-3", NodeType.FIXED, NodeStatus.AVAILABLE, 1));
        nodeRegistry.registerNode(
                new NodeInfo("worker4", "hcp-worker-node-4", NodeType.FIXED, NodeStatus.AVAILABLE, 1));
    }
}
