package com.laserpay.pdei.core;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.policy.RequirementSpec;

import java.time.Instant;

/** Builders that keep the tests readable; every field has a sane, explicit default. */
public final class TestFixtures {

    public static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    public static final String MERCHANT = "MER-0001";
    public static final String TRANSACTION = "TX-000001";

    private TestFixtures() {
    }

    public static EvidenceBuilder evidence(String id, EvidenceType type) {
        return new EvidenceBuilder(id, type);
    }

    public static RequirementSpec mandatory(EvidenceType type) {
        return new RequirementSpec(type, RequirementStrength.MANDATORY, 3, null, false, 0.0d, null);
    }

    public static RequirementSpec mandatoryRequiringProvenance(EvidenceType type) {
        return new RequirementSpec(type, RequirementStrength.MANDATORY, 3, null, true, 0.0d, null);
    }

    public static RequirementSpec recommended(EvidenceType type) {
        return new RequirementSpec(type, RequirementStrength.RECOMMENDED, 2, null, false, 0.0d, null);
    }

    public static RequirementSpec optional(EvidenceType type) {
        return new RequirementSpec(type, RequirementStrength.OPTIONAL, 1, null, false, 0.0d, null);
    }

    /** Mutable builder producing the immutable {@link EvidenceView}. */
    public static final class EvidenceBuilder {
        private final String evidenceId;
        private final EvidenceType type;
        private String merchantId = MERCHANT;
        private String transactionId = TRANSACTION;
        private EvidenceStatus status = EvidenceStatus.ACTIVE;
        private EvidenceSource source = EvidenceSource.PSP_ADAPTER;
        private String sha256 = "0".repeat(64);
        private int version = 1;
        private String parentEvidenceId;
        private String relatedEntityId;
        private String sourceEventId = "evt-1";
        private double qualityScore = 1.0d;
        private boolean provenanceVerified = true;
        private Instant createdAt = NOW.minusSeconds(3600);
        private Instant expiresAt;

        private EvidenceBuilder(String evidenceId, EvidenceType type) {
            this.evidenceId = evidenceId;
            this.type = type;
        }

        public EvidenceBuilder status(EvidenceStatus value) {
            this.status = value;
            return this;
        }

        public EvidenceBuilder transactionId(String value) {
            this.transactionId = value;
            return this;
        }

        public EvidenceBuilder sha256(String value) {
            this.sha256 = value;
            return this;
        }

        public EvidenceBuilder version(int value) {
            this.version = value;
            return this;
        }

        public EvidenceBuilder parent(String value) {
            this.parentEvidenceId = value;
            return this;
        }

        public EvidenceBuilder relatedEntityId(String value) {
            this.relatedEntityId = value;
            return this;
        }

        public EvidenceBuilder sourceEventId(String value) {
            this.sourceEventId = value;
            return this;
        }

        public EvidenceBuilder quality(double value) {
            this.qualityScore = value;
            return this;
        }

        public EvidenceBuilder provenanceVerified(boolean value) {
            this.provenanceVerified = value;
            return this;
        }

        public EvidenceBuilder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        public EvidenceBuilder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public EvidenceView build() {
            return new EvidenceView(evidenceId, merchantId, transactionId, type, status, source,
                    "key/" + evidenceId, sha256, version, evidenceId + ".pdf", "application/pdf", 1024L,
                    "summary of " + evidenceId, sourceEventId, parentEvidenceId, relatedEntityId,
                    qualityScore, provenanceVerified, createdAt, createdAt, expiresAt);
        }
    }
}
