package com.laserpay.pdei.core.ai;

import com.laserpay.pdei.core.model.InvestigationContext;
import com.laserpay.pdei.core.model.InvestigationResult;

/**
 * The only way Java talks to the AI reasoning service.
 *
 * <p>No Gemini SDK, no prompt, no provider name ever appears on this side of the boundary
 * (non-negotiable rule 7). The Python service owns provider selection; Java owns the decision about
 * whether the answer may be acted on.</p>
 */
public interface AiReasoningClient {

    /** {@code POST /v1/investigate}. Never throws on provider failure - it degrades deterministically. */
    InvestigationResult investigate(InvestigationContext context);

    /** {@code POST /v1/narrative}: an evidence-backed representment narrative. */
    String narrative(InvestigationContext context);

    /** {@code POST /v1/admission/score}: the model's own advisory view of priority. */
    AdmissionScore admissionScore(InvestigationContext context);

    /** Whether the service is currently reachable and the circuit is closed. */
    boolean isAvailable();
}
