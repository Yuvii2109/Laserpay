package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.dto.TimelineResponse;
import com.laserpay.pdei.api.dto.TransactionDetailResponse;
import com.laserpay.pdei.api.dto.TransactionResponse;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.evidence.EvidenceGraphService;
import com.laserpay.pdei.core.evidence.EvidenceService;
import com.laserpay.pdei.core.model.EvidenceGraph;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.readiness.ReadinessEngine;
import com.laserpay.pdei.core.spi.ReadinessRepositoryPort;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;
import com.laserpay.pdei.core.timeline.TimelineService;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.persistence.repository.TransactionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /transactions} routes: list, detail, timeline, readiness, evidence and graph.
 *
 * <p>Orchestration only. The scoring formula lives in {@code ReadinessEngine}, the timeline merge in
 * {@code TimelineService} and the graph projection in {@code EvidenceGraphService}; this class
 * decides which of them to call and translates the result into the HTTP shape.</p>
 */
@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    private static final Logger log = LoggerFactory.getLogger(TransactionQueryService.class);

    private final TransactionRepository transactions;
    private final TransactionRepositoryPort transactionFacts;
    private final ReadinessEngine readinessEngine;
    private final ReadinessRepositoryPort readinessRepository;
    private final EvidenceService evidenceService;
    private final EvidenceGraphService graphService;
    private final TimelineService timelineService;
    private final Clocks clock;

    public TransactionQueryService(TransactionRepository transactions,
                                   TransactionRepositoryPort transactionFacts,
                                   ReadinessEngine readinessEngine,
                                   ReadinessRepositoryPort readinessRepository,
                                   EvidenceService evidenceService,
                                   EvidenceGraphService graphService,
                                   TimelineService timelineService,
                                   Clocks clock) {
        this.transactions = transactions;
        this.transactionFacts = transactionFacts;
        this.readinessEngine = readinessEngine;
        this.readinessRepository = readinessRepository;
        this.evidenceService = evidenceService;
        this.graphService = graphService;
        this.timelineService = timelineService;
        this.clock = clock;
    }

    /** {@code GET /transactions?merchantId&band&from&to&page&size}. All filters are optional. */
    public PageResponse<TransactionResponse> search(String merchantId, ReadinessBand band,
                                                    Instant from, Instant to, Pageable pageable) {
        Page<TransactionEntity> page =
                transactions.searchByFilters(blankToNull(merchantId), band, from, to, pageable);
        return PageResponse.of(page, TransactionResponse::from);
    }

    /** {@code GET /transactions/{transactionId}}. */
    public TransactionDetailResponse get(String transactionId) {
        TransactionEntity entity = require(transactionId);
        List<EvidenceView> evidence = evidenceService.findForTransaction(transactionId);
        TransactionFacts facts = transactionFacts.findFacts(transactionId)
                .orElseGet(() -> TransactionFacts.empty(transactionId, entity.getMerchantId()));
        ReadinessSnapshot readiness = currentReadiness(transactionId, null);
        int openGaps = readiness == null ? 0 : readiness.gaps().size();
        return new TransactionDetailResponse(
                TransactionResponse.from(entity), facts, readiness, evidence, evidence.size(), openGaps);
    }

    /** {@code GET /transactions/{transactionId}/timeline}: unified event and evidence timeline. */
    public TimelineResponse timeline(String transactionId) {
        requireExists(transactionId);
        return TimelineResponse.of(transactionId, timelineService.timeline(transactionId), clock.now());
    }

    /**
     * {@code GET /transactions/{transactionId}/readiness}.
     *
     * <p>Serves the stored snapshot when there is one, because that is the number the readiness
     * worker published and the number every other surface is showing. Falls back to computing on
     * demand when nothing has been stored yet, or when the caller asked for a different reason code
     * than the stored snapshot was scored against, in which case the stored answer is simply not an
     * answer to the question asked.</p>
     */
    public ReadinessSnapshot readiness(String transactionId, DisputeReasonCode reasonCode) {
        requireExists(transactionId);
        ReadinessSnapshot snapshot = currentReadiness(transactionId, reasonCode);
        if (snapshot == null) {
            throw new NotFoundException("READINESS_SNAPSHOT", transactionId);
        }
        return snapshot;
    }

    /**
     * {@code POST /transactions/{transactionId}/readiness/recompute}.
     *
     * <p>Recomputes deterministically and persists the result. Safe to call repeatedly: gap ids are a
     * hash of their content, so a repeat run upserts the same rows instead of accumulating
     * duplicates.</p>
     *
     * <p>No {@code ReadinessRecomputed} event is published from here. That event belongs to
     * readiness-worker, and emitting it from the API too would make the same recomputation appear
     * twice in the audit trail and double-count the funnel.</p>
     */
    @Transactional
    public ReadinessSnapshot recompute(String transactionId, DisputeReasonCode reasonCode) {
        requireExists(transactionId);
        ReadinessSnapshot snapshot = readinessEngine.compute(transactionId, reasonCode);
        try {
            readinessRepository.saveSnapshot(snapshot);
        } catch (RuntimeException e) {
            // The score is already correct and is being returned; failing the request because the
            // cache write failed would hide a good answer behind a storage problem.
            log.warn("Recomputed readiness for {} but could not persist the snapshot: {}",
                    transactionId, e.toString());
        }
        return snapshot;
    }

    /** {@code GET /transactions/{transactionId}/evidence}. */
    public List<EvidenceView> evidence(String transactionId) {
        requireExists(transactionId);
        return evidenceService.findForTransaction(transactionId);
    }

    /** {@code GET /transactions/{transactionId}/graph}: nodes and edges, including CONTRADICTS. */
    public EvidenceGraph graph(String transactionId) {
        requireExists(transactionId);
        return graphService.build(transactionId);
    }

    /** Open gaps for one transaction, used by the detail page and the at-risk drill-down. */
    public List<ReadinessGap> gaps(String transactionId) {
        requireExists(transactionId);
        return readinessRepository.findGapsForTransaction(transactionId);
    }

    public TransactionEntity require(String transactionId) {
        return transactions.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("TRANSACTION", transactionId));
    }

    private void requireExists(String transactionId) {
        if (transactionId == null || !transactions.existsById(transactionId)) {
            throw new NotFoundException("TRANSACTION", transactionId);
        }
    }

    /**
     * The stored snapshot when it answers the question asked, otherwise a fresh computation.
     * Never null in practice: {@code compute} either returns a snapshot or throws.
     */
    private ReadinessSnapshot currentReadiness(String transactionId, DisputeReasonCode reasonCode) {
        Optional<ReadinessSnapshot> stored = readinessRepository.findLatest(transactionId);
        if (stored.isPresent() && stored.get().reasonCode() == reasonCode) {
            return stored.get();
        }
        try {
            return readinessEngine.compute(transactionId, reasonCode);
        } catch (RuntimeException e) {
            log.debug("Readiness computation failed for {}: {}", transactionId, e.toString());
            return stored.orElse(null);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
