package com.laserpay.pdei.statebuilder;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.core.evidence.CreateEvidenceCommand;
import com.laserpay.pdei.core.evidence.EvidenceService;
import com.laserpay.pdei.core.model.EvidenceView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A recording stand-in for {@code evidence-core}'s {@link EvidenceService}.
 *
 * <p>Tests here assert <em>which</em> artifacts a lifecycle fact derives and what content they carry.
 * Whether that content is hashed correctly, versioned correctly and audited correctly is
 * evidence-core's contract, tested there.
 */
public final class EvidenceStubs {

    private EvidenceStubs() {
    }

    /** Captures every {@link CreateEvidenceCommand} and returns a plausible view for each. */
    public static final class Recorder {

        private final List<CreateEvidenceCommand> commands = new ArrayList<>();
        private final EvidenceService service;

        private Recorder() {
            this.service = mock(EvidenceService.class);
            when(service.createEvidence(any())).thenAnswer(invocation -> {
                CreateEvidenceCommand command = invocation.getArgument(0);
                commands.add(command);
                return viewFor(command, commands.size());
            });
        }

        public EvidenceService service() {
            return service;
        }

        public List<CreateEvidenceCommand> commands() {
            return commands;
        }

        public List<EvidenceType> types() {
            return commands.stream().map(CreateEvidenceCommand::type).toList();
        }

        public CreateEvidenceCommand only() {
            if (commands.size() != 1) {
                throw new AssertionError("expected exactly one derived artifact, got " + types());
            }
            return commands.get(0);
        }

        public CreateEvidenceCommand of(EvidenceType type) {
            return commands.stream()
                    .filter(command -> command.type() == type)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no " + type + " was derived; got " + types()));
        }
    }

    public static Recorder recorder() {
        return new Recorder();
    }

    private static EvidenceView viewFor(CreateEvidenceCommand command, int sequence) {
        return new EvidenceView(
                "EV-TEST-" + sequence,
                command.merchantId(),
                command.transactionId(),
                command.type(),
                EvidenceStatus.ACTIVE,
                command.source(),
                "key/" + sequence,
                "sha-" + sequence,
                1,
                command.filename(),
                command.contentType(),
                command.content() == null ? 0L : command.content().length,
                command.summary(),
                command.sourceEventId(),
                null,
                command.relatedEntityId(),
                command.qualityScore(),
                command.provenanceVerified(),
                Instant.EPOCH,
                command.observedAt(),
                command.expiresAt());
    }
}
