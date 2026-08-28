package com.laserpay.pdei.statebuilder.handler;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.persistence.entity.CustomerEntity;
import com.laserpay.pdei.persistence.entity.MerchantEntity;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
import com.laserpay.pdei.persistence.entity.RefundEntity;
import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.statebuilder.EvidenceStubs;
import com.laserpay.pdei.statebuilder.Events;
import com.laserpay.pdei.statebuilder.Repositories;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.projection.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refunded rollup is where "recompute, never increment" earns its keep: a duplicate
 * {@code RefundProcessed} under an accumulator would double a customer's refund in the ledger.
 */
class RefundEventHandlerTest {

    private static final String PAYMENT_ID = "PAY-000123";
    private static final Instant PROCESSED_AT = Instant.parse("2026-08-27T12:00:00Z");

    private final Repositories.Store<RefundEntity> refundStore = new Repositories.Store<>();
    private final Repositories.Store<PaymentEntity> paymentStore = new Repositories.Store<>();
    private final Repositories.Store<TransactionEntity> transactionStore = new Repositories.Store<>();
    private final Repositories.Store<MerchantEntity> merchantStore = new Repositories.Store<>();
    private final Repositories.Store<CustomerEntity> customerStore = new Repositories.Store<>();
    private final Repositories.Store<OrderEntity> orderStore = new Repositories.Store<>();
    private final Repositories.Store<ShipmentEntity> shipmentStore = new Repositories.Store<>();

    private EvidenceStubs.Recorder evidence;
    private RefundEventHandler handler;

    @BeforeEach
    void setUp() {
        evidence = EvidenceStubs.recorder();
        ReferenceData referenceData = new ReferenceData(
                Repositories.merchants(merchantStore),
                Repositories.customers(customerStore),
                Repositories.transactions(transactionStore),
                Repositories.orders(orderStore),
                Repositories.shipments(shipmentStore),
                Repositories.payments(paymentStore),
                "INR");
        TransactionProjection transactionProjection = new TransactionProjection(
                Repositories.transactions(transactionStore),
                Repositories.payments(paymentStore),
                Repositories.refunds(refundStore),
                referenceData,
                "INR");
        handler = new RefundEventHandler(
                Repositories.refunds(refundStore),
                transactionProjection,
                referenceData,
                new DerivedEvidenceService(evidence.service()));

        givenCapturedPayment(1_299_900L);
    }

    /** A captured payment on the transaction, so the refund has something to be partial against. */
    private void givenCapturedPayment(long capturedMinor) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setId(Events.TRANSACTION_ID);
        transaction.setMerchantId(Events.MERCHANT_ID);
        transaction.setAmount(MoneyEmbeddable.of(capturedMinor, "INR"));
        transaction.setCapturedAmount(MoneyEmbeddable.of(capturedMinor, "INR"));
        transaction.setRefundedAmount(MoneyEmbeddable.zero("INR"));
        transaction.setStatus(TransactionStatus.CAPTURED);
        transaction.setOccurredAt(Instant.parse("2026-08-26T09:00:00Z"));
        transaction.setObservedAt(Instant.parse("2026-08-26T09:00:00Z"));
        transactionStore.put(transaction);

        PaymentEntity payment = new PaymentEntity();
        payment.setId(PAYMENT_ID);
        payment.setMerchantId(Events.MERCHANT_ID);
        payment.setTransactionId(Events.TRANSACTION_ID);
        payment.setAmount(MoneyEmbeddable.of(capturedMinor, "INR"));
        payment.setStatus("CAPTURED");
        payment.setOccurredAt(Instant.parse("2026-08-26T09:00:00Z"));
        paymentStore.put(payment);
    }

    @Test
    @DisplayName("a partial refund rolls up and moves the transaction to PARTIALLY_REFUNDED")
    void projectsPartialRefund() {
        handler.handle(processed("REF-000001", 299_900L));

        RefundEntity refund = refundStore.require("REF-000001");
        assertThat(refund.getStatus()).isEqualTo("PROCESSED");
        assertThat(refund.getAmount().getAmountMinor()).isEqualTo(299_900L);
        assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(refund.getProcessedAt()).isEqualTo(PROCESSED_AT);

        TransactionEntity transaction = transactionStore.require(Events.TRANSACTION_ID);
        assertThat(transaction.getRefundedAmount().getAmountMinor()).isEqualTo(299_900L);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);

        assertThat(evidence.types()).containsExactly(EvidenceType.REFUND_RECEIPT);
    }

    @Test
    @DisplayName("duplicate delivery: the refunded total is recomputed, so it cannot double")
    void duplicateRefundDoesNotDoubleTheRollup() {
        CanonicalEvent event = processed("REF-000001", 299_900L);

        handler.handle(event);
        handler.handle(event);

        assertThat(refundStore.size()).isEqualTo(1);
        assertThat(transactionStore.require(Events.TRANSACTION_ID)
                .getRefundedAmount().getAmountMinor()).isEqualTo(299_900L);
        assertThat(evidence.types()).containsExactly(EvidenceType.REFUND_RECEIPT);
    }

    @Test
    @DisplayName("refunding the full captured amount moves the transaction to REFUNDED")
    void fullRefundMarksTheTransactionRefunded() {
        handler.handle(processed("REF-000001", 1_000_000L));
        handler.handle(processed("REF-000002", 299_900L));

        TransactionEntity transaction = transactionStore.require(Events.TRANSACTION_ID);
        assertThat(transaction.getRefundedAmount().getAmountMinor()).isEqualTo(1_299_900L);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
    }

    @Test
    @DisplayName("out-of-order rejection: a RefundCreated arriving after the processing is ignored")
    void rejectsOlderRefundCreated() {
        handler.handle(processed("REF-000001", 299_900L));

        handler.handle(Events.of(EventType.RefundCreated, "REF-000001",
                PROCESSED_AT.minusSeconds(3600), """
                        { "refundId": "REF-000001", "transactionId": "TX-82918",
                          "paymentId": "PAY-000123", "amount": %s, "requestedBy": "CUSTOMER",
                          "createdAt": "2026-08-27T11:00:00Z" }
                        """.formatted(Events.money(299_900L, "INR"))));

        RefundEntity refund = refundStore.require("REF-000001");
        assertThat(refund.getStatus()).isEqualTo("PROCESSED");
        assertThat(ProjectionWatermark.lastOccurredAt(refund.getMetadata())).isEqualTo(PROCESSED_AT);
        assertThat(transactionStore.require(Events.TRANSACTION_ID)
                .getRefundedAmount().getAmountMinor()).isEqualTo(299_900L);
    }

    private static CanonicalEvent processed(String refundId, long amountMinor) {
        return Events.of(EventType.RefundProcessed, refundId, PROCESSED_AT, """
                { "refundId": "%s", "transactionId": "TX-82918", "paymentId": "PAY-000123",
                  "amount": %s, "settlementReference": "txn_900", "isPartial": true,
                  "processedAt": "2026-08-27T12:00:00Z" }
                """.formatted(refundId, Events.money(amountMinor, "INR")));
    }
}
