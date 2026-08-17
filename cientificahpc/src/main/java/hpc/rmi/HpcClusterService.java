package hpc.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;
import hpc.jobs.JobStatus;

/**
 * Remote interface exposed by the RMI server to clients.
 *
 * This is the "MPI Jobs" service from the architecture diagram, backed by
 * Java RMI. Clients obtain a stub of this interface via the RMI Registry
 * and call these methods as if the server object were local.
 */
public interface HpcClusterService extends Remote {

    /**
     * Authenticates the user via AuthProvider and, if valid, enqueues a
     * new job for the given code and data references.
     *
     * This is the only point where authentication happens in the current
     * scope (no authorization/permissions yet — that is deferred, see the
     * project notes on Perfiles de usuario).
     *
     * codeReference and dataReference are lightweight references, not the
     * actual file contents — large datasets must never be sent as a single
     * RMI parameter (memory and marshalling cost). HomeFetcher is the
     * component responsible for resolving these references into real files
     * on the cluster's shared storage:
     *   - Test implementation: the reference is already a local path the
     *     client wrote directly onto the shared storage.
     *   - Real implementation (once the Home integration is ready): the
     *     reference points into the user's Home, and HomeFetcher fetches
     *     the files from there (stage-in) before the job runs.
     * Neither JobQueue nor LanguageRunner need to know which implementation
     * is active.
     *
     * @param username      the username
     * @param credential    the password (or token, depending on what is decided later)
     * @param codeReference reference to the C source code to compile and run
     * @param dataReference reference to the input dataset for the job (assumed
     *                      CSV for now — to be confirmed with the professor)
     * @return the generated job id, used to check status/result afterwards
     * @throws AuthenticationException if the credentials are invalid
     * @throws RemoteException         for RMI communication issues
     */
    String submitJob(String username, String credential, String codeReference, String dataReference)
            throws RemoteException, AuthenticationException;

    /**
     * Checks the current status of a job (QUEUED, RUNNING, COMPLETED or FAILED).
     *
     * @param jobId the id returned by submitJob
     * @return the job's current status, or null if the id does not exist
     */
    JobStatus getStatus(String jobId) throws RemoteException;

    /**
     * Retrieves the output of a completed job.
     *
     * @param jobId the id returned by submitJob
     * @return the job's result, or null if it is not COMPLETED yet
     *         (the client should check getStatus first)
     */
    String getResult(String jobId) throws RemoteException;
}
