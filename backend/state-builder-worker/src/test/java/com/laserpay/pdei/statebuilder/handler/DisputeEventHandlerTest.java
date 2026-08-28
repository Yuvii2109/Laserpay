package com.laserpay.pdei.statebuilder.handler;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.persistence.entity.CustomerEntity;
import com.laserpay.pdei.persistence.entity.DisputeEntity;
import com.laserpay.pdei.persistence.entity.MerchantEntity;
import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
import com.laserpay.pdei.persistence.entity.RefundEntity;
import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.statebuilder.Events;
import com.laserpay.pdei.statebuilder.Repositories;
import com.laserpay.pdei.statebuilder.forward.EventForwarder;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.projection.TransactionStatus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisputeEventHandlerTest {

    private static final String DISPUTE_ID = "DSP-000055";
    private static final Instant OPENED_AT = Instant.parse("2026-08-28T07:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-29T07:00:00Z");

    private final Repositories.Store<DisputeEntity> disputeStore = new Repositories.Store<>();
    private final Repositories.Store<TransactionEntity> transactionStore = new Repositories.Store<>();
    private final Repositories.Store<MerchantEntity> merchantStore = new Repositories.Store<>();
    private final Repositories.Store<CustomerEntity> customerStore = new Repositories.Store<>();
    private final Repositories.Store<PaymentEntity> paymentStore = new Repositories.Store<>();
    private final Repositories.Store<RefundEntity> refundStore = new Repositories.Store<>();
    private final Repositories.Store<OrderEntity> orderStore = new Repositories.Store<>();
    private final Repositories.Store<ShipmentEntity> shipmentStore = new Repositories.Store<>();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private DisputeEventHandler handler;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

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
        handler = new DisputeEventHandler(
                Repositories.disputes(disputeStore),
                transactionProjection,
                referenceData,
                new EventForwarder(kafkaTemplate, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("DisputeCreated is projected, moves the transaction to CHARGEBACK, and is forwarded")
    void projectsAndForwardsDisputeCreated() {
        handler.handle(created());

        DisputeEntity dispute = disputeStore.require(DISPUTE_ID);
        assertThat(dispute.getReasonCode()).isEqualTo(DisputeReasonCode.GOODS_NOT_RECEIVED);
        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(dispute.getTransactionId()).isEqualTo(Events.TRANSACTION_ID);
        assertThat(dispute.getAmount().getAmountMinor()).isEqualTo(1_299_900L);
        assertThat(dispute.getDeadlineAt()).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(dispute.getMetadata()).containsEntry("networkReasonCode", "13.1");

        assertThat(transactionStore.require(Events.TRANSACTION_ID).getStatus())
                .isEqualTo(TransactionStatus.CHARGEBACK);

        ProducerRecord<String, Object> forwarded = captureForward();
        assertThat(forwarded.topic()).isEqualTo(Topics.DISPUTE_EVENTS);
        assertThat(forwarded.key()).isEqualTo(Events.MERCHANT_ID + ":" + DISPUTE_ID);
        assertThat(forwarded.value()).isInstanceOf(CanonicalEvent.class);
        assertThat(((CanonicalEvent) forwarded.value()).eventId())
                .isEqualTo(created().eventId());
    }

    @Test
    @DisplayName("duplicate delivery: projected once, but forwarded both times so no signal is lost")
    void duplicateDeliveryStillForwards() {
        CanonicalEvent event = created();

        handler.handle(event);
        handler.handle(event);

        assertThat(disputeStore.size()).isEqualTo(1);
        // The orchestrator dedupes on eventId; dropping the second forward could strand a workflow.
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("out-of-order rejection: an older DisputeUpdated cannot regress the status")
    void rejectsOlderUpdate() {
        handler.handle(created());
        handler.handle(updated(UPDATED_AT, "REPRESENTMENT_PREPARED"));
        assertThat(disputeStore.require(DISPUTE_ID).getStatus())
                .isEqualTo(DisputeStatus.REPRESENTMENT_PREPARED);

        // A redelivered "OPEN" update with an earlier occurredAt must not pull the case backwards.
        handler.handle(updated(OPENED_AT.plusSeconds(60), "OPEN"));

        assertThat(disputeStore.require(DISPUTE_ID).getStatus())
                .isEqualTo(DisputeStatus.REPRESENTMENT_PREPARED);
    }

    @Test
    @DisplayName("DisputeClosed records the outcome and maps it onto the dispute status")
    void projectsClosure() {
        handler.handle(created());
        handler.handle(Events.of(EventType.DisputeClosed, DISPUTE_ID,
                Instant.parse("2026-09-15T00:00:00Z"), """
                        { "disputeId": "DSP-000055", "outcome": "WON",
                          "closedAt": "2026-09-15T00:00:00Z" }
                        """));

        DisputeEntity dispute = disputeStore.require(DISPUTE_ID);
        assertThat(dispute.getOutcome()).isEqualTo("WON");
        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(dispute.getClosedAt()).isEqualTo(Instant.parse("2026-09-15T00:00:00Z"));
    }

    @Test
    @DisplayName("an update that overtakes its creation is forwarded but not projected")
    void updateBeforeCreationIsForwardedOnly() {
        handler.handle(updated(UPDATED_AT, "EVIDENCE_GATHERING"));

        assertThat(disputeStore.size()).isZero();
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    // --- helpers --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> captureForward() {
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(
                (Class<ProducerRecord<String, Object>>) (Class<?>) ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static CanonicalEvent created() {
        return Events.of(EventType.DisputeCreated, DISPUTE_ID, OPENED_AT, """
                { "disputeId": "DSP-000055", "transactionId": "TX-82918", "paymentId": "PAY-000123",
                  "merchantId": "MER-0001", "reasonCode": "GOODS_NOT_RECEIVED",
                  "networkReasonCode": "13.1", "disputedAmount": %s, "status": "OPEN",
                  "deadlineAt": "2026-09-10T00:00:00Z", "receivedAt": "2026-08-28T07:00:00Z" }
                """.formatted(Events.money(1_299_900L, "INR")));
    }

    private static CanonicalEvent updated(Instant at, String status) {
        return Events.of(EventType.DisputeUpdated, DISPUTE_ID, at, """
                { "disputeId": "DSP-000055", "transactionId": "TX-82918", "status": "%s",
                  "updatedAt": "%s" }
                """.formatted(status, at));
    }
}
