package com.laserpay.pdei.common.domain;

/**
 * Verdict of an investigation (PLATFORM-CONTRACT section 6, AI contract section 9.2).
 *
 * <p>Produced either deterministically by evidence-core or proposed by the AI reasoning service.
 * A proposed {@link #DEFENDABLE} is rejected outright when any MANDATORY requirement is
 * unsatisfied (PLATFORM-CONTRACT section 9.3 rule 7) - the classification is a claim, and claims
 * are checked against the record before they are allowed to influence anything.
 */
public enum InvestigationClassification {
    /** The evidence supports contesting the dispute. */
    DEFENDABLE,
    /** Contestable in principle, but the evidence set is thin. */
    WEAK,
    /** The evidence supports the cardholder; contesting would waste the representment. */
    INDEFENSIBLE,
    /** Not enough evidence to judge either way. */
    INSUFFICIENT_EVIDENCE,
    /** Evidence points both ways, typically because of unresolved contradictions. */
    AMBIGUOUS
}
