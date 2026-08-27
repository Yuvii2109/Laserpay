package com.laserpay.pdei.core.search;

import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.SearchPage;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-text search over evidence, backed by the Postgres {@code tsvector} columns and GIN indexes
 * added in {@code V10__fts.sql}.
 *
 * <p>Postgres FTS is used deliberately instead of a search cluster: the corpus is evidence metadata
 * and extracted document text for a single tenant's transactions, which fits comfortably in the
 * database we already run. Adding Elasticsearch for it would be infrastructure without a workload
 * (design principle 5.5).</p>
 *
 * <p>Raw user input never reaches Postgres. It is tokenised on non-alphanumerics and rebuilt as a
 * safe AND-ed {@code tsquery} with a prefix match on the final token, so type-ahead works and no
 * input can be interpreted as query syntax.</p>
 */
public class EvidenceSearchService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSearchService.class);

    private final EvidenceRepositoryPort repository;

    public EvidenceSearchService(EvidenceRepositoryPort repository) {
        this.repository = repository;
    }

    public SearchPage<EvidenceView> search(EvidenceSearchQuery query) {
        if (query == null) {
            return SearchPage.empty(0, 25);
        }
        String tsQuery = Text.toTsQuery(query.q());
        log.debug("evidence search merchantId={} type={} status={} tsquery='{}'",
                query.merchantId(), query.type(), query.status(), tsQuery);
        return repository.search(tsQuery, query.merchantId(), query.type(), query.status(),
                query.transactionId(), query.page(), query.size());
    }

    /** The exact {@code tsquery} string a raw input produces - exposed for debugging and tests. */
    public String toTsQuery(String raw) {
        return Text.toTsQuery(raw);
    }
}
