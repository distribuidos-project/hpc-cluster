package hpc.rmi;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import hpc.auth.AuthProvider;
import hpc.jobs.Job;
import hpc.jobs.JobQueue;
import hpc.jobs.JobStatus;

/**
 * Concrete implementation of the HpcClusterService remote contract.
 *
 * This class is a pure orchestrator: it authenticates the caller through
 * AuthProvider and delegates job bookkeeping to JobQueue. It only depends
 * on interfaces, never on concrete implementations — which one is wired
 * in (FakeAuthProvider vs a future real one, an in-memory JobQueue vs
 * another one) is decided by whoever constructs this server, not by this
 * class itself.
 *
 * It does not know how a job actually gets compiled/executed (that is
 * LanguageRunner/CRunner's responsibility, invoked later by whatever
 * component polls the queue) nor how codeReference/dataReference get
 * resolved into real files (that is HomeFetcher's responsibility).
 */
public class RmiClusterServer extends UnicastRemoteObject implements HpcClusterService {

    private final AuthProvider authProvider;
    private final JobQueue jobQueue;

    public RmiClusterServer(AuthProvider authProvider, JobQueue jobQueue) throws RemoteException {
        super();
        this.authProvider = authProvider;
        this.jobQueue = jobQueue;
    }

    @Override
    public String submitJob(String username, String credential, String codeReference, String dataReference)
            throws RemoteException, AuthenticationException {
        if (!authProvider.authenticate(username, credential)) {
            throw new AuthenticationException("Invalid credentials for user: " + username);
        }
        return jobQueue.enqueue(username, codeReference, dataReference);
    }

    @Override
    public JobStatus getStatus(String jobId) throws RemoteException {
        Job job = jobQueue.getJob(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job id: " + jobId);
        }
        return job.getStatus();
    }

    @Override
    public String getResult(String jobId) throws RemoteException {
        Job job = jobQueue.getJob(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job id: " + jobId);
        }
        return job.getResult();
    }

    @Override
    public String getErrorMessage(String jobId) throws RemoteException {
        Job job = jobQueue.getJob(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job id: " + jobId);
        }
        return job.getErrorMessage();
    }

    @Override
    public void retryJob(String username, String credential, String jobId)
            throws RemoteException, AuthenticationException {
        if (!authProvider.authenticate(username, credential)) {
            throw new AuthenticationException("Invalid credentials for user: " + username);
        }
        jobQueue.retry(jobId);
    }
}
