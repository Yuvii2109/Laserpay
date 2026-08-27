package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.core.model.TransactionFacts;

import java.util.Optional;

/**
 * Read port over the transaction aggregate ({@code transactions}, {@code payments}, {@code orders},
 * {@code order_lines}, {@code shipments}, {@code deliveries}, {@code refunds}, {@code communications}).
 *
 * <p>Read-only on purpose: evidence-core reasons about financial state, it never writes it. State is
 * written by {@code state-builder-worker} from canonical events.</p>
 */
public interface TransactionRepositoryPort {

    Optional<TransactionFacts> findFacts(String transactionId);

    Optional<String> findMerchantId(String transactionId);

    boolean exists(String transactionId);
}
