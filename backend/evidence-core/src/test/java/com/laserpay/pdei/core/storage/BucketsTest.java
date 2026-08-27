package com.laserpay.pdei.core.storage;

import com.laserpay.pdei.common.domain.EvidenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The MinIO key layout of platform contract 11. */
class BucketsTest {

    @Test
    @DisplayName("evidence keys follow merchant/transaction/type/evidence/version/filename")
    void evidenceKeyLayout() {
        String key = Buckets.evidenceKey("MER-0001", "TX-000001", EvidenceType.DELIVERY_PROOF,
                "EV-0009", 2, "pod.pdf");

        assertThat(key).isEqualTo("MER-0001/TX-000001/DELIVERY_PROOF/EV-0009/v2/pod.pdf");
    }

    @Test
    @DisplayName("package keys follow merchant/case/representment-case-vN.zip")
    void packageKeyLayout() {
        assertThat(Buckets.packageBundleKey("MER-0001", "CASE-77", 3))
                .isEqualTo("MER-0001/CASE-77/representment-CASE-77-v3.zip");
        assertThat(Buckets.packageManifestKey("MER-0001", "CASE-77"))
                .isEqualTo("MER-0001/CASE-77/manifest.json");
    }

    @Test
    @DisplayName("filenames cannot escape their key prefix")
    void filenamesAreSanitised() {
        assertThat(Buckets.safeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(Buckets.safeFilename("C:\\temp\\proof of delivery.pdf"))
                .isEqualTo("proof_of_delivery.pdf");
        assertThat(Buckets.safeFilename("  ")).isEqualTo("artifact.bin");
        assertThat(Buckets.safeFilename(null)).isEqualTo("artifact.bin");
    }

    @Test
    @DisplayName("a blank id segment never produces an empty path element")
    void blankSegmentsAreReplaced() {
        String key = Buckets.evidenceKey(null, "TX-1", EvidenceType.INVOICE, "EV-1", 1, "a.pdf");

        assertThat(key).isEqualTo("unknown/TX-1/INVOICE/EV-1/v1/a.pdf");
        assertThat(key).doesNotContain("//");
    }

    @Test
    @DisplayName("content types are inferred from the filename when the uploader sends none")
    void contentTypeInference() {
        assertThat(Buckets.contentTypeFor("pod.pdf")).isEqualTo("application/pdf");
        assertThat(Buckets.contentTypeFor("signature.PNG")).isEqualTo("image/png");
        assertThat(Buckets.contentTypeFor("bundle.zip")).isEqualTo("application/zip");
        assertThat(Buckets.contentTypeFor("unknown.bin")).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("the user metadata keys are the ones the contract requires on every object")
    void userMetadataKeys() {
        assertThat(Buckets.X_AMZ_META_SHA256).isEqualTo("x-amz-meta-sha256");
        assertThat(Buckets.X_AMZ_META_SOURCE_EVENT_ID).isEqualTo("x-amz-meta-source-event-id");
        assertThat(Buckets.X_AMZ_META_EVIDENCE_ID).isEqualTo("x-amz-meta-evidence-id");
        assertThat(Buckets.X_AMZ_META_VERSION).isEqualTo("x-amz-meta-version");
    }
}
