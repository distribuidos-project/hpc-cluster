package hpc.home;

/**
 * Publishes a job's result back to the user's Home/Repository, next to
 * the original code/data files (stage-out) — the mirror operation of
 * HomeFetcher (stage-in).
 *
 * Same swappable-implementation pattern as HomeFetcher:
 *   - TestHomePublisher: writes the result file next to wherever the
 *     original reference already lived, without contacting a real Home.
 *   - RepositoryHomePublisher: pending Home/Repository integration.
 *
 * Fire-and-forget by design: this is an additional copy of the result
 * left in the Home for the user's convenience. The authoritative result
 * the client relies on is still the one returned by
 * HpcClusterService.getResult(jobId); a failure to publish here should
 * never turn a successful job into a failed one.
 */
public interface HomePublisher {

    /**
     * Publishes a local result file back to the Home, alongside the
     * original reference (e.g. the job's codeReference).
     *
     * @param reference       reference to the job's original code (or
     *                        data), used to know where in the Home this
     *                        result belongs
     * @param localResultPath path to the result file already written on
     *                        the cluster's shared storage
     */
    void publish(String reference, String localResultPath);
}
