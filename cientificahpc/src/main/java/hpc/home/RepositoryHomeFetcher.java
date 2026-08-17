package hpc.home;

/**
 * Real implementation of HomeFetcher: fetches the file from the user's
 * Home in the general Repository (stage-in), once that integration is
 * ready.
 *
 * NOT YET IMPLEMENTED — pending coordination with the teammate building
 * the Home/Repository, to decide the exact access mechanism (NFS mount
 * to the general Repository, or an API call to the Shared File Server).
 * Left here as a placeholder so the rest of the system already has a
 * clear extension point to plug into once that decision is made.
 */
public class RealHomeFetcher implements HomeFetcher {

    @Override
    public String resolve(String reference, String workingDirectory) {
        throw new UnsupportedOperationException(
                "RealHomeFetcher not implemented yet — pending Home/Repository integration.");
    }
}
