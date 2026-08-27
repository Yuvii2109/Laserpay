package com.laserpay.pdei.core.dispute;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.model.DisputeView;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.util.CoreErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dispute lifecycle: open, transition, close.
 *
 * <p>Status transitions are validated against an explicit table rather than trusted from callers. A
 * dispute that has been submitted cannot silently drop back to evidence gathering, and a terminal
 * dispute never re-opens - the workflow would otherwise be able to double-submit a representment.</p>
 *
 * <p>Creation is idempotent per transaction: an open dispute on a transaction is returned rather than
 * duplicated, so a redelivered PSP webhook cannot create two cases for the same chargeback.</p>
 */
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);
    private static final String ENTITY_TYPE = "DISPUTE";

    /** Legal transitions. Terminal statuses map to an empty set. */
    private static final Map<DisputeStatus, Set<DisputeStatus>> TRANSITIONS =
            new EnumMap<>(DisputeStatus.class);

    static {
        TRANSITIONS.put(DisputeStatus.OPEN, EnumSet.of(DisputeStatus.EVIDENCE_GATHERING,
                DisputeStatus.UNDER_INVESTIGATION, DisputeStatus.AWAITING_HUMAN_REVIEW,
                DisputeStatus.WITHDRAWN, DisputeStatus.EXPIRED));
        TRANSITIONS.put(DisputeStatus.EVIDENCE_GATHERING, EnumSet.of(DisputeStatus.UNDER_INVESTIGATION,
                DisputeStatus.AWAITING_HUMAN_REVIEW, DisputeStatus.REPRESENTMENT_PREPARED,
                DisputeStatus.WITHDRAWN, DisputeStatus.EXPIRED));
        TRANSITIONS.put(DisputeStatus.UNDER_INVESTIGATION, EnumSet.of(DisputeStatus.EVIDENCE_GATHERING,
                DisputeStatus.AWAITING_HUMAN_REVIEW, DisputeStatus.REPRESENTMENT_PREPARED,
                DisputeStatus.WITHDRAWN, DisputeStatus.EXPIRED));
        TRANSITIONS.put(DisputeStatus.AWAITING_HUMAN_REVIEW, EnumSet.of(DisputeStatus.EVIDENCE_GATHERING,
                DisputeStatus.REPRESENTMENT_PREPARED, DisputeStatus.WITHDRAWN, DisputeStatus.EXPIRED,
                DisputeStatus.LOST));
        TRANSITIONS.put(DisputeStatus.REPRESENTMENT_PREPARED, EnumSet.of(DisputeStatus.SUBMITTED,
                DisputeStatus.AWAITING_HUMAN_REVIEW, DisputeStatus.WITHDRAWN, DisputeStatus.EXPIRED));
        TRANSITIONS.put(DisputeStatus.SUBMITTED, EnumSet.of(DisputeStatus.WON, DisputeStatus.LOST,
                DisputeStatus.EXPIRED));
        TRANSITIONS.put(DisputeStatus.WON, EnumSet.noneOf(DisputeStatus.class));
        TRANSITIONS.put(DisputeStatus.LOST, EnumSet.noneOf(DisputeStatus.class));
        TRANSITIONS.put(DisputeStatus.EXPIRED, EnumSet.noneOf(DisputeStatus.class));
        TRANSITIONS.put(DisputeStatus.WITHDRAWN, EnumSet.noneOf(DisputeStatus.class));
    }

    private final CaseRepositoryPort repository;
    private final PolicyEngine policyEngine;
    private final EventPublisherPort publisher;
    private final AuditRecorder audit;
    private final Clocks clock;

    public DisputeService(CaseRepositoryPort repository, PolicyEngine policyEngine,
                          EventPublisherPort publisher, AuditRecorder audit, Clocks clock) {
        this.repository = repository;
        this.policyEngine = policyEngine;
        this.publisher = publisher;
        this.audit = audit;
        this.clock = clock;
    }

    /** Open a dispute, or return the one already open on this transaction. */
    public DisputeView create(CreateDisputeCommand command) {
        CoreErrors.requireValue(command, "command");
        CoreErrors.requireText(command.merchantId(), "merchantId");
        CoreErrors.requireText(command.transactionId(), "transactionId");
        CoreErrors.requireValue(command.reasonCode(), "reasonCode");

        Optional<DisputeView> existing = repository.findOpenDisputeForTransaction(command.transactionId());
        if (existing.isPresent()) {
            log.debug("dispute {} already open on transaction {}", existing.get().disputeId(),
                    command.transactionId());
            return existing.get();
        }

        Instant now = clock.now();
        Instant openedAt = command.openedAt() == null ? now : command.openedAt();
        Instant deadlineAt = command.deadlineAt() != null
                ? command.deadlineAt()
                : policyEngine.responseDeadline(
                        policyEngine.applicablePolicy(command.merchantId(), command.reasonCode()), openedAt);

        DisputeView dispute = new DisputeView(
                Ids.dispute(),
                command.merchantId(),
                command.transactionId(),
                command.reasonCode(),
                DisputeStatus.OPEN,
                command.amount(),
                command.networkCaseRef(),
                command.source(),
                openedAt,
                deadlineAt,
                null,
                now);

        repository.insertDispute(dispute);
        audit.record(AuditCommand.of(ENTITY_TYPE, dispute.disputeId(), dispute.merchantId(),
                        "DISPUTE_CREATED", command.actor(), ActorType.SYSTEM)
                .withAfter(dispute)
                .withCorrelationId(command.correlationId()));
        publish(EventType.DisputeCreated, dispute, command.correlationId(), command.sourceEventId());
        log.info("opened dispute {} on transaction {} reasonCode={} deadlineAt={}", dispute.disputeId(),
                dispute.transactionId(), dispute.reasonCode(), deadlineAt);
        return dispute;
    }

    /** Transition a dispute, validating the move against the transition table. */
    public DisputeView updateStatus(String disputeId, DisputeStatus target, String actor, String reason) {
        DisputeView before = require(disputeId);
        if (before.status() == target) {
            return before;
        }
        Set<DisputeStatus> allowed = TRANSITIONS.getOrDefault(before.status(),
                EnumSet.noneOf(DisputeStatus.class));
        if (!allowed.contains(target)) {
            throw CoreErrors.conflict("illegal dispute transition " + before.status() + " -> " + target
                    + " on " + disputeId);
        }

        Instant now = clock.now();
        boolean terminal = TRANSITIONS.getOrDefault(target, Set.of()).isEmpty();
        Instant closedAt = terminal ? now : null;
        repository.updateDisputeStatus(disputeId, target, now, closedAt);

        DisputeView after = new DisputeView(before.disputeId(), before.merchantId(), before.transactionId(),
                before.reasonCode(), target, before.amount(), before.networkCaseRef(), before.source(),
                before.openedAt(), before.deadlineAt(), closedAt, now);

        audit.record(AuditCommand.of(ENTITY_TYPE, disputeId, before.merchantId(),
                        "DISPUTE_" + target, actor, ActorType.SYSTEM)
                .withBefore(before)
                .withAfter(after));
        publish(terminal ? EventType.DisputeClosed : EventType.DisputeUpdated, after, null, reason);
        return after;
    }

    /** Close a dispute with a terminal outcome. */
    public DisputeView close(String disputeId, DisputeStatus outcome, String actor, String reason) {
        if (!TRANSITIONS.getOrDefault(outcome, EnumSet.allOf(DisputeStatus.class)).isEmpty()) {
            throw CoreErrors.invalid(outcome + " is not a terminal dispute status");
        }
        return updateStatus(disputeId, outcome, actor, reason);
    }

    /** Expire every dispute whose deadline has passed without a submission. */
    public int expireOverdue(String merchantId, int limit) {
        int expired = 0;
        Instant now = clock.now();
        for (DisputeView dispute : repository.findDisputes(merchantId, DisputeStatus.OPEN, null, 0, limit)) {
            if (dispute.pastDeadline(now)) {
                updateStatus(dispute.disputeId(), DisputeStatus.EXPIRED, "SYSTEM", "deadline passed");
                expired++;
            }
        }
        return expired;
    }

    public Optional<DisputeView> find(String disputeId) {
        return repository.findDispute(disputeId);
    }

    public DisputeView require(String disputeId) {
        return repository.findDispute(CoreErrors.requireText(disputeId, "disputeId"))
                .orElseThrow(() -> CoreErrors.notFound(ENTITY_TYPE, disputeId));
    }

    public List<DisputeView> list(String merchantId, DisputeStatus status, DisputeReasonCode reasonCode,
                                  int page, int size) {
        return repository.findDisputes(merchantId, status, reasonCode, page, size);
    }

    /** Legal next statuses, exposed so the UI can render only valid actions. */
    public Set<DisputeStatus> allowedTransitions(DisputeStatus from) {
        return Set.copyOf(TRANSITIONS.getOrDefault(from, EnumSet.noneOf(DisputeStatus.class)));
    }

    private void publish(EventType eventType, DisputeView dispute, String correlationId, String causationId) {
        CanonicalEvent event = new CanonicalEvent(
                Ids.eventId(),
                eventType,
                1,
                AggregateType.DISPUTE,
                dispute.disputeId(),
                dispute.merchantId(),
                correlationId == null ? Ids.eventId() : correlationId,
                causationId,
                clock.now(),
                clock.now(),
                EventSource.INTERNAL,
                eventType.name() + ":" + dispute.disputeId() + ":" + dispute.status(),
                Json.tree(dispute));
        publisher.publish(Topics.DISPUTE_EVENTS, event);
    }
}
