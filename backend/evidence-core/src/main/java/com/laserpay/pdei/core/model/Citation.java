package com.laserpay.pdei.core.model;

/** One entry of {@link InvestigationResult#citations()} (platform contract 9.2). */
public record Citation(String claim, String evidenceId) {
}
