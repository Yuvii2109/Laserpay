package com.laserpay.pdei.orchestrator.submission;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The deterministic stand-in for a real PSP or card-network submission API.
 *
 * <p><b>Real submission is out of scope for this baseline</b>, and this class is deliberately named
 * so that no reader, log line, event payload or UI badge can mistake it for the real thing. It:</p>
 * <ul>
 *   <li>derives the submission id and the network reference from
 *       {@code sha256(caseId : packageVersion : bundleSha256)}, so a retried activity produces
 *       exactly the same identifiers instead of a second submission;</li>
 *   <li>accepts any package that carries a bundle key and a bundle hash, and rejects one that does
 *       not - submitting bytes we cannot name or verify is the one failure mode worth simulating;</li>
 *   <li>records {@code simulated = true} on every result.</li>
 * </ul>
 *
 * <p>Replacing it means implementing {@link NetworkSubmitter} against a real API and registering
 * that bean instead; the orchestrator needs no other change.</p>
 */
@Component
public class SimulatedNetworkSubmitter implements NetworkSubmitter {

    private static final Logger log = LoggerFactory.getLogger(SimulatedNetworkSubmitter.class);

    /** Name recorded on every receipt. Reads as what it is. */
    public static final String NAME = "SIMULATED_NETWORK";
    /** Prefix of the fabricated network reference; also reads as what it is. */
    public static final String REFERENCE_PREFIX = "SIMNET-";
    private static final String SUBMISSION_PREFIX = "SUB-";
    private static final int REFERENCE_LENGTH = 16;
    private static final String METRIC = "pdei_case_submissions_total";

    private final Clocks clock;
    private final MeterRegistry meterRegistry;

    public SimulatedNetworkSubmitter(Clocks clock, MeterRegistry meterRegistry) {
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public NetworkSubmissionResult submit(NetworkSubmissionRequest request) {
        boolean submittable = request != null
                && request.bundleObjectKey() != null && !request.bundleObjectKey().isBlank()
                && request.bundleSha256() != null && !request.bundleSha256().isBlank();

        if (!submittable) {
            count("REJECTED");
            String detail = "package is missing a bundle object key or its sha256; nothing was submitted";
            log.error("simulated submission rejected for case {}: {}",
                    request == null ? null : request.caseId(), detail);
            return new NetworkSubmissionResult(null, null, false, detail, NAME, true, clock.now());
        }

        String fingerprint = fingerprint(request);
        NetworkSubmissionResult result = new NetworkSubmissionResult(
                SUBMISSION_PREFIX + fingerprint,
                REFERENCE_PREFIX + fingerprint,
                true,
                "accepted by the simulated network; no real PSP was contacted",
                NAME,
                true,
                clock.now());

        count("ACCEPTED");
        log.info("SIMULATED submission for case {} v{}: reference={} bundle={} sha256={} ({} artifacts,"
                        + " {} bytes) - no real network was contacted",
                request.caseId(), request.packageVersion(), result.networkReference(),
                request.bundleObjectKey(), request.bundleSha256(), request.itemCount(),
                request.bundleSizeBytes());
        return result;
    }

    /**
     * Deterministic identity of one submission. Same case, same package version, same bytes gives
     * the same reference forever - which is what makes a retry safe.
     */
    static String fingerprint(NetworkSubmissionRequest request) {
        String material = request.caseId() + ":" + request.packageVersion() + ":"
                + request.bundleSha256();
        return Hashes.sha256(material.getBytes(StandardCharsets.UTF_8))
                .substring(0, REFERENCE_LENGTH)
                .toUpperCase(Locale.ROOT);
    }

    private void count(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(METRIC, "submitter", NAME, "outcome", outcome).increment();
        } catch (RuntimeException e) {
            // metrics never block a submission
        }
    }
}
