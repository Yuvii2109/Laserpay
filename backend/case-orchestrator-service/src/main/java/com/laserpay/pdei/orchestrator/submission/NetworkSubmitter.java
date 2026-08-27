package com.laserpay.pdei.orchestrator.submission;

/**
 * The boundary between PDEI and whatever actually receives a representment.
 *
 * <p>Only one implementation exists in this baseline, {@link SimulatedNetworkSubmitter}, and it is
 * named so that nobody can mistake it for a real integration. A future PSP adapter implements this
 * interface and nothing else in the orchestrator changes: the workflow, the activity and the
 * receipt shape are already correct.</p>
 *
 * <p><b>Contract for any implementation:</b> {@link #submit} must be idempotent on
 * {@code (caseId, packageVersion, bundleSha256)}. The activity that calls it can be retried, and
 * submitting the same package twice to a card network is a real-world incident, not a warning.</p>
 */
public interface NetworkSubmitter {

    /** Short, stable name recorded on the receipt and in the audit trail. */
    String name();

    /** True when this submitter does not contact a real network. */
    boolean isSimulated();

    NetworkSubmissionResult submit(NetworkSubmissionRequest request);
}
