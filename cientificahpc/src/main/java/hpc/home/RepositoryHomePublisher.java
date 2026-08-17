package hpc.home;

/**
 * Real implementation of HomePublisher: pushes the result file back into
 * the user's Home in the general Repository (stage-out), once that
 * integration is ready.
 *
 * NOT YET IMPLEMENTED — pending coordination with the teammate building
 * the Home/Repository, same as RepositoryHomeFetcher.
 */
public class RepositoryHomePublisher implements HomePublisher {

    @Override
    public void publish(String reference, String localResultPath) {
        throw new UnsupportedOperationException(
                "RepositoryHomePublisher not implemented yet — pending Home/Repository integration.");
    }
}
