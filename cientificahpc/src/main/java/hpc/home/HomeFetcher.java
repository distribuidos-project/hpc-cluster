package hpc.home;

/**
 * Resolves a job's code/data reference into a real file already present
 * on the cluster's shared storage, ready for LanguageRunner to use.
 *
 * Two implementations exist:
 *   - TestHomeFetcher: the reference is already a local path (the client
 *     wrote the file directly onto the shared storage); used for
 *     development/testing without depending on the real Home integration.
 *   - RealHomeFetcher: fetches the file from the user's Home in the
 *     general Repository (stage-in), once that integration is ready.
 * Neither JobQueue nor LanguageRunner need to know which one is active.
 */
public interface HomeFetcher {

    /**
     * Resolves a reference into a file inside workingDirectory.
     *
     * @param reference        the code or data reference from the job
     * @param workingDirectory the job's working directory on the cluster's
     *                         shared storage, where the resolved file must end up
     * @return the local path to the resolved file
     */
    String resolve(String reference, String workingDirectory);
}
