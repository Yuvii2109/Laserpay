package com.laserpay.pdei.api.service;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.event.EventType;
import java.util.EnumSet;
import java.util.Set;

/**
 * The three human decisions the API exposes on a case, and what each one means to the state machine.
 *
 * <p>All three are delivered to the workflow as the single {@code humanDecision} signal of contract
 * section 10; the distinction travels in the signal payload. Modelling them as one enum here keeps
 * the legal source statuses, the target status and the emitted event in one place instead of spread
 * across three near-identical controller methods.</p>
 *
 * <p>Why REJECT lands on {@code AWAITING_EVIDENCE}: {@code CaseStatus} has no REJECTED constant, and
 * inventing one would break the shared enum. A human rejecting a prepared representment is saying
 * "this is not good enough yet", which is precisely the awaiting-evidence state, and the emitted
 * {@code CaseEscalated} event is what tells the workflow a human intervened.</p>
 */
public enum CaseDecision {

    /** Human approves the prepared representment. */
    APPROVE("approve",
            EnumSet.of(CaseStatus.AWAITING_APPROVAL, CaseStatus.INVESTIGATING),
            CaseStatus.PREPARED,
            EventType.CasePrepared),

    /** Human refuses it and sends the case back for more evidence. */
    REJECT("reject",
            EnumSet.of(CaseStatus.AWAITING_APPROVAL, CaseStatus.PREPARED, CaseStatus.INVESTIGATING),
            CaseStatus.AWAITING_EVIDENCE,
            EventType.CaseEscalated),

    /** Human submits the representment to the network. */
    SUBMIT("submit",
            EnumSet.of(CaseStatus.PREPARED, CaseStatus.AWAITING_APPROVAL),
            CaseStatus.SUBMITTED,
            EventType.CaseSubmitted);

    /** The Temporal signal every decision is delivered through (contract section 10). */
    public static final String SIGNAL_NAME = "humanDecision";

    private final String wire;
    private final Set<CaseStatus> allowedFrom;
    private final CaseStatus target;
    private final EventType eventType;

    CaseDecision(String wire, Set<CaseStatus> allowedFrom, CaseStatus target, EventType eventType) {
        this.wire = wire;
        this.allowedFrom = allowedFrom;
        this.target = target;
        this.eventType = eventType;
    }

    public String wire() {
        return wire;
    }

    public Set<CaseStatus> allowedFrom() {
        return allowedFrom;
    }

    public CaseStatus target() {
        return target;
    }

    public EventType eventType() {
        return eventType;
    }

    public boolean isLegalFrom(CaseStatus status) {
        return status != null && allowedFrom.contains(status);
    }
}
