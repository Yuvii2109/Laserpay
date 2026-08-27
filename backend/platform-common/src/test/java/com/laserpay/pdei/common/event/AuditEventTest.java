package com.laserpay.pdei.common.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventTest {

    private static final Instant AT = Instant.parse("2026-08-26T10:15:30.123Z");

    private static AuditEvent auditRecord(String auditId, String action, String previousHash) {
        ObjectNode after = Json.mapper().createObjectNode();
        after.put("status", "ACTIVE");
        return new AuditEvent(auditId, "EVIDENCE", "EV-1092", "MER-0001", action,
                "state-builder-worker", ActorType.SYSTEM, AT, "corr-1", null, after,
                previousHash, null);
    }

    @Test
    void computeHashIsDeterministicAndExcludesTheHashField() {
        AuditEvent unsealed = auditRecord("AUD-1", "EVIDENCE_ADDED", Hashes.GENESIS_HASH);
        String hash = unsealed.computeHash();

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(unsealed.computeHash()).isEqualTo(hash);

        AuditEvent sealed = unsealed.withHash();
        assertThat(sealed.hash()).isEqualTo(hash);
        // Storing the hash must not change the preimage, or verification could never succeed.
        assertThat(sealed.computeHash()).isEqualTo(hash);
        assertThat(sealed.verifyHash()).isTrue();
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        AuditEvent base = auditRecord("AUD-1", "EVIDENCE_ADDED", Hashes.GENESIS_HASH).withHash();

        assertThat(auditRecord("AUD-1", "EVIDENCE_INVALIDATED", Hashes.GENESIS_HASH).computeHash())
                .isNotEqualTo(base.hash());
        assertThat(auditRecord("AUD-2", "EVIDENCE_ADDED", Hashes.GENESIS_HASH).computeHash())
                .isNotEqualTo(base.hash());
        assertThat(auditRecord("AUD-1", "EVIDENCE_ADDED", Hashes.sha256Hex("other")).computeHash())
                .isNotEqualTo(base.hash());
    }

    @Test
    void missingPreviousHashSeedsFromGenesis() {
        assertThat(auditRecord("AUD-1", "EVIDENCE_ADDED", null).previousHash())
                .isEqualTo(Hashes.GENESIS_HASH);
        assertThat(auditRecord("AUD-1", "EVIDENCE_ADDED", "  ").previousHash())
                .isEqualTo(Hashes.GENESIS_HASH);
    }

    @Test
    void chainedRecordsVerifyAsALinkedList() {
        List<AuditEvent> chain = new ArrayList<>();
        AuditEvent previous = null;
        for (int i = 1; i <= 5; i++) {
            AuditEvent next = auditRecord("AUD-" + i, "ACTION_" + i, null).chainedAfter(previous);
            chain.add(next);
            previous = next;
        }

        AuditEvent before = null;
        for (AuditEvent link : chain) {
            assertThat(link.verifyLink(before)).isTrue();
            before = link;
        }
    }

    @Test
    void tamperingWithAnEarlyRecordBreaksTheChainAtThatPoint() {
        AuditEvent first = auditRecord("AUD-1", "CASE_OPENED", null).chainedAfter(null);
        AuditEvent second = auditRecord("AUD-2", "CASE_PREPARED", null).chainedAfter(first);

        AuditEvent tamperedFirst = auditRecord("AUD-1", "CASE_CLOSED", null).chainedAfter(null);

        assertThat(second.verifyLink(first)).isTrue();
        assertThat(second.verifyLink(tamperedFirst)).isFalse();
    }

    @Test
    void mandatoryFieldsAreEnforced() {
        assertThatThrownBy(() -> auditRecord(null, "A", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("auditId");
        assertThatThrownBy(() -> auditRecord("AUD-1", "  ", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("action");
    }

    @Test
    void defaultsFillActorAndActorType() {
        AuditEvent event = new AuditEvent("AUD-9", "CASE", "CASE-1", "MER-1", "CASE_OPENED",
                null, null, AT, null, null, null, null, null);

        assertThat(event.actor()).isEqualTo("system");
        assertThat(event.actorType()).isEqualTo(ActorType.SYSTEM);
    }
}
