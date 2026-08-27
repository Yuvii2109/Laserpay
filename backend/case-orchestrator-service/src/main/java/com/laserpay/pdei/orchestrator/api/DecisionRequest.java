package com.laserpay.pdei.orchestrator.api;

/**
 * Body of the human-decision routes. {@code actor} is the reviewer's identity and is recorded on
 * the case row and in the hash-chained audit log, so it is required in anything but a dev
 * environment.
 */
public record DecisionRequest(String actor, String notes) {

    public String actorOrSystem() {
        return actor == null || actor.isBlank() ? "UNKNOWN_OPERATOR" : actor;
    }
}
