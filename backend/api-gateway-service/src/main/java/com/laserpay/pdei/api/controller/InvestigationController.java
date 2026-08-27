package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.InvestigationResponse;
import com.laserpay.pdei.api.service.InvestigationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/investigations/{investigationId}} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <p>Read only, and there is no route here that could ever be anything else. An investigation is a
 * record of what a model proposed and what the deterministic gate decided about it; it is written by
 * the workflow after validation, never by a client. Exposing a write path would let a caller assert
 * a conclusion the safety rules never approved.</p>
 */
@RestController
@RequestMapping("/api/v1/investigations")
@Tag(name = "investigations", description = "AI investigation results and their safety verdicts")
public class InvestigationController {

    private final InvestigationQueryService investigations;

    public InvestigationController(InvestigationQueryService investigations) {
        this.investigations = investigations;
    }

    @GetMapping("/{investigationId}")
    @Operation(summary = "One investigation",
            description = "The model's proposal, the deterministic verdict applied to it, and the "
                    + "per-claim validation findings.")
    public InvestigationResponse get(@PathVariable("investigationId") String investigationId) {
        return investigations.get(investigationId);
    }
}
