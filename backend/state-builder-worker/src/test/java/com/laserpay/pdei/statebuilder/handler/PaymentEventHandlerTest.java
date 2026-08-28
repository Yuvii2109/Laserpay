package com.laserpay.pdei.statebuilder.handler;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
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

class PaymentEventHandlerTest {

    private static final String PAYMENT_ID = "PAY-000123";
    private static final Instant CREATED_AT = Instant.parse("2026-08-26T08:00:00Z");
    private static final Instant AUTHORIZED_AT = Instant.parse("2026-08-26T08:05:00Z");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-26T09:00:00Z");

    private final Repositories.Store<PaymentEntity> paymentStore = new Repositories.Store<>();
    private final Repositories.Store<TransactionEntity> transactionStore = new Repositories.Store<>();
    private final Repositories.Store<com.laserpay.pdei.persistence.entity.MerchantEntity> merchantStore =
            new Repositories.Store<>();
    private final Repositories.Store<com.laserpay.pdei.persistence.entity.CustomerEntity> customerStore =
            new Repositories.Store<>();
    private final Repositories.Store<com.laserpay.pdei.persistence.entity.RefundEntity> refundStore =
            new Repositories.Store<>();
    private final Repositories.Store<com.laserpay.pdei.persistence.entity.OrderEntity> orderStore =
            new Repositories.Store<>();
    private final Repositories.Store<com.laserpay.pdei.persistence.entity.ShipmentEntity> shipmentStore =
            new Repositories.Store<>();

    private EvidenceStubs.Recorder evidence;
    private PaymentEventHandler handler;

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
        handler = new PaymentEventHandler(
                Repositories.payments(paymentStore),
                transactionProjection,
                new DerivedEvidenceService(evidence.service()));
    }

    // --- projection -----------------------------------------------------------------------------

    @Test
    @DisplayName("a capture projects the payment, rolls up the transaction and derives PAYMENT_PROOF")
    void projectsCapture() {
        handler.handle(captured(CAPTURED_AT));

        PaymentEntity payment = paymentStore.require(PAYMENT_ID);
        assertThat(payment.getStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getTransactionId()).isEqualTo(Events.TRANSACTION_ID);
        assertThat(payment.getAmount().getAmountMinor()).isEqualTo(1_299_900L);
        assertThat(payment.getAmount().getCurrency()).isEqualTo("INR");
        assertThat(payment.getCapturedAt()).isEqualTo(CAPTURED_AT);

        TransactionEntity transaction = transactionStore.require(Events.TRANSACTION_ID);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(transaction.getCapturedAmount().getAmountMinor()).isEqualTo(1_299_900L);
        assertThat(transaction.getLastEventId()).isNotNull();

        assertThat(evidence.types()).containsExactly(EvidenceType.PAYMENT_PROOF);
        assertThat(evidence.only().relatedEntityId()).isEqualTo(PAYMENT_ID);
        assertThat(evidence.only().transactionId()).isEqualTo(Events.TRANSACTION_ID);
    }

    @Test
    @DisplayName("the merchant row is created on demand rather than dead-lettering the event")
    void createsMerchantOnDemand() {
        handler.handle(captured(CAPTURED_AT));

        assertThat(merchantStore.contains(Events.MERCHANT_ID)).isTrue();
        assertThat(merchantStore.require(Events.MERCHANT_ID).getDefaultCurrency()).isEqualTo("INR");
    }

    // --- idempotency ----------------------------------------------------------------------------

    @Test
    @DisplayName("duplicate delivery: applying the same event twice changes nothing the second time")
    void duplicateDeliveryIsANoOp() {
        CanonicalEvent capture = captured(CAPTURED_AT);

        handler.handle(capture);
        long versionAfterFirst = paymentStore.require(PAYMENT_ID).getVersion();
        String watermarkAfterFirst =
                ProjectionWatermark.lastEventId(paymentStore.require(PAYMENT_ID).getMetadata());

        handler.handle(capture);

        PaymentEntity payment = paymentStore.require(PAYMENT_ID);
        assertThat(payment.getStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getVersion()).isEqualTo(versionAfterFirst);
        assertThat(ProjectionWatermark.lastEventId(payment.getMetadata()))
                .isEqualTo(watermarkAfterFirst);

        // The captured rollup did not double, and no second artifact was derived.
        assertThat(transactionStore.require(Events.TRANSACTION_ID)
                .getCapturedAmount().getAmountMinor()).isEqualTo(1_299_900L);
        assertThat(evidence.types()).containsExactly(EvidenceType.PAYMENT_PROOF);
    }

    // --- out-of-order ---------------------------------------------------------------------------

    @Test
    @DisplayName("out-of-order rejection: an older event never overwrites newer projected state")
    void rejectsOlderEvent() {
        handler.handle(captured(CAPTURED_AT));
        assertThat(paymentStore.require(PAYMENT_ID).getStatus()).isEqualTo("CAPTURED");

        // PaymentAuthorized happened at 08:05 but arrives after the 09:00 capture.
        handler.handle(authorized(AUTHORIZED_AT));

        PaymentEntity payment = paymentStore.require(PAYMENT_ID);
        assertThat(payment.getStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getAuthorizedAt()).isNull();
        assertThat(payment.getAvsResult()).isNull();

        // The transaction status did not regress either.
        assertThat(transactionStore.require(Events.TRANSACTION_ID).getStatus())
                .isEqualTo(TransactionStatus.CAPTURED);
        // ...and no evidence was derived from the rejected event.
        assertThat(evidence.types()).containsExactly(EvidenceType.PAYMENT_PROOF);
    }

    @Test
    @DisplayName("in-order delivery applies every step and derives the authorization artifacts")
    void appliesInOrderSequence() {
        handler.handle(created(CREATED_AT));
        handler.handle(authorized(AUTHORIZED_AT));
        handler.handle(captured(CAPTURED_AT));

        PaymentEntity payment = paymentStore.require(PAYMENT_ID);
        assertThat(payment.getStatus()).isEqualTo("CAPTURED");
        assertThat(payment.getAuthorizedAt()).isEqualTo(AUTHORIZED_AT);
        assertThat(payment.getAvsResult()).isEqualTo("Y");
        assertThat(payment.getDeviceFingerprint()).isEqualTo("fp_abc");
        assertThat(payment.getCardLast4()).isEqualTo("4242");

        assertThat(evidence.types()).containsExactly(
                EvidenceType.AVS_CVV_RESULT,
                EvidenceType.DEVICE_FINGERPRINT,
                EvidenceType.PAYMENT_PROOF);
    }

    @Test
    @DisplayName("an authorization with no AVS or device data derives nothing rather than empty proof")
    void derivesNothingWithoutVerificationData() {
        handler.handle(Events.of(EventType.PaymentAuthorized, PAYMENT_ID, AUTHORIZED_AT, """
                { "paymentId": "PAY-000123", "transactionId": "TX-82918",
                  "authorizedAmount": %s, "authorizedAt": "2026-08-26T08:05:00Z" }
                """.formatted(Events.money(1_299_900L, "INR"))));

        assertThat(evidence.types()).isEmpty();
        assertThat(paymentStore.require(PAYMENT_ID).getStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("a failed payment leaves the transaction below CAPTURED and derives nothing")
    void projectsFailure() {
        handler.handle(Events.of(EventType.PaymentFailed, PAYMENT_ID, CREATED_AT, """
                { "paymentId": "PAY-000123", "transactionId": "TX-82918",
                  "amount": %s, "failureCode": "insufficient_funds",
                  "failureReason": "Card declined", "failedAt": "2026-08-26T08:00:00Z" }
                """.formatted(Events.money(1_299_900L, "INR"))));

        PaymentEntity payment = paymentStore.require(PAYMENT_ID);
        assertThat(payment.getStatus()).isEqualTo("FAILED");
        assertThat(payment.getFailureCode()).isEqualTo("insufficient_funds");
        assertThat(transactionStore.require(Events.TRANSACTION_ID).getStatus())
                .isEqualTo(TransactionStatus.FAILED);
        assertThat(transactionStore.require(Events.TRANSACTION_ID)
                .getCapturedAmount().getAmountMinor()).isZero();
        assertThat(evidence.types()).isEmpty();
    }

    // --- fixtures -------------------------------------------------------------------------------

    private static CanonicalEvent created(Instant at) {
        return Events.of(EventType.PaymentCreated, PAYMENT_ID, at, """
                { "paymentId": "PAY-000123", "transactionId": "TX-82918", "customerId": "CUS-77",
                  "amount": %s, "method": "CARD", "cardLast4": "4242", "cardNetwork": "VISA",
                  "createdAt": "2026-08-26T08:00:00Z" }
                """.formatted(Events.money(1_299_900L, "INR")));
    }

    private static CanonicalEvent authorized(Instant at) {
        return Events.of(EventType.PaymentAuthorized, PAYMENT_ID, at, """
                { "paymentId": "PAY-000123", "transactionId": "TX-82918",
                  "authorizedAmount": %s, "authorizationCode": "AUTH1",
                  "avsResult": "Y", "cvvResult": "M", "deviceFingerprint": "fp_abc",
                  "authorizedAt": "2026-08-26T08:05:00Z" }
                """.formatted(Events.money(1_299_900L, "INR")));
    }

    private static CanonicalEvent captured(Instant at) {
        return Events.of(EventType.PaymentCaptured, PAYMENT_ID, at, """
                { "paymentId": "PAY-000123", "transactionId": "TX-82918",
                  "capturedAmount": %s, "settlementReference": "txn_552",
                  "capturedAt": "2026-08-26T09:00:00Z" }
                """.formatted(Events.money(1_299_900L, "INR")));
    }
}
