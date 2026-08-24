package hpc.client;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

import hpc.jobs.JobStatus;
import hpc.rmi.HpcClusterService;

/**
 * Manual test harness for HpcClusterService — not part of the production
 * system. Used to validate the end-to-end flow against a running Main
 * server: authenticate, submit a job, poll its status, and retrieve the
 * result or error. Also supports checking status and retrying an existing
 * job, useful for testing the node-failure scenario.
 *
 * Now that Main wires the real JwtAuthProvider, the credential is a real
 * RS256 JWT issued by cca-soap (its "autenticar" SOAP operation), not a
 * hardcoded test password — obtain one by calling cca-soap and paste it
 * in, or wire it up through whatever client cca-web/cca-soap already
 * expose.
 *
 * Usage (run from any machine that can reach the master on port 1099):
 *   java -cp target/classes hpc.client.TestClient <masterHost> <username> <jwtToken> submit <codeReference> <dataReference>
 *   java -cp target/classes hpc.client.TestClient <masterHost> <username> <jwtToken> status <jobId>
 *   java -cp target/classes hpc.client.TestClient <masterHost> <username> <jwtToken> retry <jobId>
 */
public class TestClient {

    private static final int RMI_PORT = 1099;
    private static final String SERVICE_NAME = "HpcClusterService";
    private static final long POLL_INTERVAL_MS = 2000;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            printUsage();
            return;
        }

        String masterHost = args[0];
        String username = args[1];
        String token = args[2];
        String command = args[3];

        Registry registry = LocateRegistry.getRegistry(masterHost, RMI_PORT);
        HpcClusterService cluster = (HpcClusterService) registry.lookup(SERVICE_NAME);

        switch (command) {
            case "submit" -> {
                if (args.length < 6) {
                    printUsage();
                    return;
                }
                String jobId = cluster.submitJob(username, token, args[4], args[5]);
                System.out.println("Job submitted: " + jobId);
                waitAndPrint(cluster, jobId);
            }
            case "status" -> {
                if (args.length < 5) {
                    printUsage();
                    return;
                }
                waitAndPrint(cluster, args[4]);
            }
            case "retry" -> {
                if (args.length < 5) {
                    printUsage();
                    return;
                }
                cluster.retryJob(username, token, args[4]);
                System.out.println("Retry requested for job " + args[4]);
                waitAndPrint(cluster, args[4]);
            }
            default -> printUsage();
        }
    }

    /** Polls until the job leaves QUEUED/RUNNING, then prints the outcome. */
    private static void waitAndPrint(HpcClusterService cluster, String jobId) throws Exception {
        JobStatus status;
        do {
            status = cluster.getStatus(jobId);
            System.out.println("Status: " + status);
            if (status == JobStatus.QUEUED || status == JobStatus.RUNNING) {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } while (status == JobStatus.QUEUED || status == JobStatus.RUNNING);

        if (status == JobStatus.COMPLETED) {
            System.out.println("Result:\n" + cluster.getResult(jobId));
        } else {
            System.out.println("Error:\n" + cluster.getErrorMessage(jobId));
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  submit: TestClient <masterHost> <username> <jwtToken> submit <codeReference> <dataReference>");
        System.out.println("  status: TestClient <masterHost> <username> <jwtToken> status <jobId>");
        System.out.println("  retry:  TestClient <masterHost> <username> <jwtToken> retry <jobId>");
    }
}
