package hpc.jobs;

/**
 * FIFO queue of jobs. Only one job runs at a time: while a job is RUNNING,
 * the rest stay QUEUED, and the next one starts automatically once the
 * current one finishes (COMPLETED or FAILED).
 *
 * Used internally by the RMI server; not exposed directly to remote clients.
 */
public interface JobQueue {

    /**
     * Adds a new job to the end of the queue.
     *
     * @param owner         username of whoever submitted the job
     * @param codeReference reference to the source code to compile and run
     *                      (resolved later by HomeFetcher, not raw content)
     * @param dataReference reference to the input dataset for the job
     *                      (resolved later by HomeFetcher, not raw content)
     * @return the generated job id, used later to check status/result
     */
    String enqueue(String owner, String codeReference, String dataReference);

    /**
     * Retrieves a job by id (its current status, result or error message).
     */
    Job getJob(String jobId);

    /**
     * Returns the next QUEUED job and marks it as RUNNING, or null if
     * there is nothing queued or a job is already running.
     *
     * Called by the orchestrator's execution loop, not by clients.
     */
    Job pollNext();

    /**
     * Marks a job as COMPLETED and stores its result.
     */
    void markCompleted(String jobId, String result);

    /**
     * Marks a job as FAILED and stores the error details (e.g. a node
     * went down mid-execution, or the compilation failed).
     */
    void markFailed(String jobId, String errorMessage);

    /**
     * Re-submits a FAILED job from scratch: resets it to QUEUED so it
     * runs again from the beginning. Does not attempt to resume the
     * previous execution (MPI does not support that without extensions
     * like ULFM, which are out of scope for this project).
     */
    void retry(String jobId);
}
