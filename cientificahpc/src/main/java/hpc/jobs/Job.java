package hpc.jobs;

import java.io.Serializable;
import java.time.Instant;

/**
 * A job submitted by a client: references to its source code and input
 * data, current status, and (once finished) its result or error details.
 *
 * codeReference and dataReference are NOT the file contents — they are
 * lightweight references (e.g. a local path once resolved, or a pointer
 * into the user's Home) that HomeFetcher resolves into actual files on
 * the cluster's shared storage. Large datasets never travel as a single
 * in-memory String/RMI parameter; only these small references do.
 */
public class Job implements Serializable {

    private final String id;
    private final String owner; // username of whoever submitted it, from AuthProvider
    private final String codeReference;
    private final String dataReference; // input dataset, assumed CSV for now — confirm format with the professor
    private final Instant submittedAt;

    private JobStatus status;
    private String result;        // output, once COMPLETED
    private String errorMessage;  // details, if FAILED

    public Job(String id, String owner, String codeReference, String dataReference) {
        this.id = id;
        this.owner = owner;
        this.codeReference = codeReference;
        this.dataReference = dataReference;
        this.submittedAt = Instant.now();
        this.status = JobStatus.QUEUED;
    }

    public String getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getCodeReference() {
        return codeReference;
    }

    public String getDataReference() {
        return dataReference;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
