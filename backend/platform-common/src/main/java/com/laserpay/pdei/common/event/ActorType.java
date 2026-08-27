package com.laserpay.pdei.common.event;

/**
 * Who performed an audited action (docs/SHARED-LIBRARY-API.md section 1.3).
 *
 * <p>{@code AI_SERVICE} exists so that every model-influenced action is distinguishable in the
 * audit trail forever. Note that an {@code AI_SERVICE} actor can only ever appear on
 * <em>proposal</em> actions: the model never mutates financial state (reference section 39.2), so
 * a state transition attributed to {@code AI_SERVICE} is by definition a bug or a breach.
 */
public enum ActorType {
    SYSTEM,
    MERCHANT_USER,
    OPERATOR,
    AI_SERVICE,
    SIMULATOR
}
