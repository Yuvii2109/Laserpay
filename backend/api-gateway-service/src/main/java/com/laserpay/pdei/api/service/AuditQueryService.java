package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.support.Paging;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.audit.ChainVerification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /audit} routes: the hash-chained log and its verification.
 *
 * <p>Read only, and it must stay that way. The audit log is append-only and the append happens
 * inside the operation being audited, never through an HTTP call; an endpoint that could write here
 * would make the chain forgeable and the whole provenance argument worthless.</p>
 */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditRecorder auditRecorder;

    public AuditQueryService(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    /**
     * {@code GET /audit?entityId=&entityType=&page=}.
     *
     * <p>Either filter by entity (both parts required together: an entity id without its type can
     * collide across tables) or by merchant with optional actor and time bounds.</p>
     */
    public PageResponse<AuditEvent> find(String entityType, String entityId, String merchantId,
                                         String actor, Instant from, Instant to, int page, int size) {
        // Validated before the repository sees them: a negative page becomes a negative SQL OFFSET.
        int safePage = Paging.page(page);
        int safeSize = Paging.size(size, Paging.MAX_SIZE);

        boolean hasEntity = notBlank(entityType) || notBlank(entityId);
        if (hasEntity) {
            if (!notBlank(entityType) || !notBlank(entityId)) {
                throw new ValidationException(
                        "entityType and entityId must be supplied together",
                        Map.of("entityType", String.valueOf(entityType),
                                "entityId", String.valueOf(entityId)));
            }
            List<AuditEvent> slice =
                    auditRecorder.findByEntity(entityType, entityId, safePage, safeSize);
            return PageResponse.ofSlice(slice, safePage, safeSize);
        }
        if (!notBlank(merchantId)) {
            throw ValidationException.field("merchantId",
                    "is required when no entityType and entityId are supplied");
        }
        List<AuditEvent> slice =
                auditRecorder.find(merchantId, blankToNull(actor), from, to, safePage, safeSize);
        return PageResponse.ofSlice(slice, safePage, safeSize);
    }

    /**
     * {@code GET /audit/verify-chain?merchantId=}.
     *
     * <p>Recomputes every hash in the merchant's chain and reports the first entry whose stored hash
     * no longer matches its content. A broken chain is a 200 with {@code intact: false}, not an
     * error: the caller asked a question and this is the answer, and turning it into a 5xx would
     * make an integrity failure look like an outage.</p>
     */
    public ChainVerification verifyChain(String merchantId) {
        if (!notBlank(merchantId)) {
            throw ValidationException.field("merchantId", "is required");
        }
        return auditRecorder.verifyChain(merchantId);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value : null;
    }
}
