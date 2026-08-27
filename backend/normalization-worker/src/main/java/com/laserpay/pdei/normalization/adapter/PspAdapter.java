package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.normalization.support.Payloads;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Payment service provider webhooks: payments, refunds and disputes.
 *
 * <p>Models the shape every card PSP converged on - a thin event wrapper around a nested
 * {@code data.object}, amounts quoted in <em>minor units</em>, timestamps as epoch seconds, and a
 * lower-case currency code. Both the wrapped ({@code data.object.*}) and flat ({@code *}) layouts
 * are accepted because sandbox and production payloads differ on exactly that point.
 *
 * <p>This is the only adapter that produces {@code DISPUTE} events; those flow on the canonical
 * topic and state-builder-worker forwards them to {@code pdei.dispute.events.v1} for the
 * orchestrator.
 */
public class PspAdapter extends AbstractSourceAdapter {

    private static final Map<String, EventType> MAPPINGS = Map.ofEntries(
            Map.entry("payment_intent.created", EventType.PaymentCreated),
            Map.entry("payment.created", EventType.PaymentCreated),
            Map.entry("charge.created", EventType.PaymentCreated),
            Map.entry("payment_intent.authorized", EventType.PaymentAuthorized),
            Map.entry("payment.authorized", EventType.PaymentAuthorized),
            Map.entry("charge.authorized", EventType.PaymentAuthorized),
            Map.entry("payment_intent.succeeded", EventType.PaymentCaptured),
            Map.entry("payment.captured", EventType.PaymentCaptured),
            Map.entry("charge.captured", EventType.PaymentCaptured),
            Map.entry("payment_intent.payment_failed", EventType.PaymentFailed),
            Map.entry("payment.failed", EventType.PaymentFailed),
            Map.entry("charge.failed", EventType.PaymentFailed),
            Map.entry("refund.created", EventType.RefundCreated),
            Map.entry("charge.refund.created", EventType.RefundCreated),
            Map.entry("refund.succeeded", EventType.RefundProcessed),
            Map.entry("charge.refunded", EventType.RefundProcessed),
            Map.entry("dispute.created", EventType.DisputeCreated),
            Map.entry("charge.dispute.created", EventType.DisputeCreated),
            Map.entry("dispute.updated", EventType.DisputeUpdated),
            Map.entry("charge.dispute.updated", EventType.DisputeUpdated),
            Map.entry("dispute.closed", EventType.DisputeClosed),
            Map.entry("charge.dispute.closed", EventType.DisputeClosed));

    private static final Set<String> ALIASES = Set.of(
            "PSP", "PSP_ADAPTER", "PAYMENTS", "stripe", "razorpay", "adyen", "payu", "cashfree");

    public PspAdapter(String defaultCurrency) {
        super("PSP", ALIASES, MAPPINGS, defaultCurrency);
    }

    @Override
    public EventSource eventSource() {
        return EventSource.PSP_ADAPTER;
    }

    @Override
    protected String transactionIdHint(RawEventEnvelope raw) {
        return prefixedFrom(raw.body(), IdPrefix.TRANSACTION,
                "transactionId", "transaction_id",
                "data.object.metadata.transaction_id", "data.object.metadata.transactionId",
                "metadata.transaction_id", "metadata.transactionId");
    }

    @Override
    protected CanonicalEvent map(RawEventEnvelope raw, EventType eventType, Instant observedAt) {
        JsonNode body = raw.body();
        JsonNode object = Payloads.first(body, "data.object", "payload.payment", "payload", "object");
        JsonNode source = object == null ? body : object;

        return switch (eventType) {
            case PaymentCreated, PaymentAuthorized, PaymentCaptured, PaymentFailed ->
                    payment(raw, eventType, source, observedAt);
            case RefundCreated, RefundProcessed -> refund(raw, eventType, source, observedAt);
            case DisputeCreated, DisputeUpdated, DisputeClosed -> dispute(raw, eventType, source, observedAt);
            default -> throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "PspAdapter does not produce " + eventType);
        };
    }

    // --- payments -------------------------------------------------------------------------------

    private CanonicalEvent payment(RawEventEnvelope raw, EventType eventType, JsonNode source,
                                   Instant observedAt) {
        String paymentId = prefixedFrom(source, IdPrefix.PAYMENT, "paymentId", "payment_id", "id",
                "payment_intent", "paymentIntentId");
        String transactionId = prefixedFrom(source, IdPrefix.TRANSACTION, "transactionId",
                "transaction_id", "metadata.transaction_id", "metadata.transactionId", "order_id");
        String currency = currencyOf(source);
        Money amount = Payloads.money(source, currency, "amount", "amount_minor",
                "amountMinor", "amount_captured", "amount_authorized");

        ObjectNode payload = Payloads.object();
        Payloads.putText(payload, "paymentId", paymentId);
        Payloads.putText(payload, "transactionId", transactionId);
        Payloads.putText(payload, "customerId",
                prefixedFrom(source, IdPrefix.CUSTOMER, "customerId", "customer_id", "customer"));
        Payloads.putText(payload, "psp", Payloads.textOr(raw.body(), raw.sourceSystem(),
                "psp", "provider", "gateway"));
        Payloads.putText(payload, "pspReference", Payloads.text(source, "id", "reference",
                "acquirer_reference", "settlementReference", "settlement_reference"));
        Payloads.putText(payload, "method", upper(Payloads.text(source, "method", "payment_method",
                "payment_method_types.0")));
        Payloads.putText(payload, "cardNetwork", upper(Payloads.text(source, "cardNetwork",
                "card.brand", "payment_method_details.card.brand", "network")));
        Payloads.putText(payload, "cardLast4", Payloads.text(source, "cardLast4", "card.last4",
                "payment_method_details.card.last4"));

        Instant occurredAt = occurredAtFor(eventType, source, raw);
        switch (eventType) {
            case PaymentCreated -> {
                Payloads.putMoney(payload, "amount", amount);
                Payloads.putInstant(payload, "createdAt", occurredAt);
            }
            case PaymentAuthorized -> {
                Payloads.putMoney(payload, "authorizedAmount", amount);
                Payloads.putText(payload, "authorizationCode", Payloads.text(source, "authorizationCode",
                        "authorization_code", "auth_code"));
                Payloads.putText(payload, "avsResult", upper(Payloads.text(source, "avsResult",
                        "avs_result", "payment_method_details.card.checks.address_line1_check")));
                Payloads.putText(payload, "cvvResult", upper(Payloads.text(source, "cvvResult",
                        "cvv_result", "payment_method_details.card.checks.cvc_check")));
                Payloads.putText(payload, "threeDsResult", upper(Payloads.text(source, "threeDsResult",
                        "three_ds_result", "payment_method_details.card.three_d_secure.result")));
                Payloads.putText(payload, "deviceFingerprint", Payloads.text(source, "deviceFingerprint",
                        "device_fingerprint", "payment_method_details.card.fingerprint"));
                Payloads.putText(payload, "ipAddress", Payloads.text(source, "ipAddress", "ip_address",
                        "client_ip"));
                Payloads.putInstant(payload, "authorizedAt", occurredAt);
            }
            case PaymentCaptured -> {
                Payloads.putMoney(payload, "capturedAmount", amount);
                Payloads.putText(payload, "settlementReference", Payloads.text(source,
                        "settlementReference", "settlement_reference", "balance_transaction"));
                Payloads.putInstant(payload, "capturedAt", occurredAt);
            }
            case PaymentFailed -> {
                Payloads.putMoney(payload, "amount", amount);
                Payloads.putText(payload, "failureCode", Payloads.text(source, "failureCode",
                        "failure_code", "last_payment_error.code", "error.code"));
                Payloads.putText(payload, "failureReason", Payloads.text(source, "failureReason",
                        "failure_message", "last_payment_error.message", "error.description"));
                Payloads.putInstant(payload, "failedAt", occurredAt);
            }
            default -> throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "unreachable payment branch for " + eventType);
        }

        return envelope(raw, eventType, paymentId, occurredAt, observedAt, payload);
    }

    // --- refunds --------------------------------------------------------------------------------

    private CanonicalEvent refund(RawEventEnvelope raw, EventType eventType, JsonNode source,
                                  Instant observedAt) {
        String refundId = prefixedFrom(source, IdPrefix.REFUND, "refundId", "refund_id", "id");
        String currency = currencyOf(source);
        Money amount = Payloads.money(source, currency, "amount", "amount_minor", "amount_refunded");

        ObjectNode payload = Payloads.object();
        Payloads.putText(payload, "refundId", refundId);
        Payloads.putText(payload, "paymentId", prefixedFrom(source, IdPrefix.PAYMENT, "paymentId",
                "payment_id", "charge", "payment_intent"));
        Payloads.putText(payload, "transactionId", prefixedFrom(source, IdPrefix.TRANSACTION,
                "transactionId", "transaction_id", "metadata.transaction_id"));
        Payloads.putMoney(payload, "amount", amount);
        Payloads.putText(payload, "reason", Payloads.text(source, "reason", "refund_reason"));

        Instant occurredAt = occurredAtFor(eventType, source, raw);
        if (eventType == EventType.RefundCreated) {
            Payloads.putText(payload, "requestedBy", upper(Payloads.textOr(source, "MERCHANT",
                    "requestedBy", "requested_by", "initiator")));
            Payloads.putInstant(payload, "createdAt", occurredAt);
        } else {
            Payloads.putText(payload, "settlementReference", Payloads.text(source,
                    "settlementReference", "settlement_reference", "balance_transaction"));
            payload.put("isPartial", Payloads.bool(source, false, "isPartial", "is_partial", "partial"));
            Payloads.putInstant(payload, "processedAt", occurredAt);
        }
        return envelope(raw, eventType, refundId, occurredAt, observedAt, payload);
    }

    // --- disputes -------------------------------------------------------------------------------

    private CanonicalEvent dispute(RawEventEnvelope raw, EventType eventType, JsonNode source,
                                   Instant observedAt) {
        String disputeId = prefixedFrom(source, IdPrefix.DISPUTE, "disputeId", "dispute_id", "id");
        String currency = currencyOf(source);
        Money amount = Payloads.money(source, currency, "disputedAmount", "amount", "amount_minor");

        ObjectNode payload = Payloads.object();
        Payloads.putText(payload, "disputeId", disputeId);
        Payloads.putText(payload, "transactionId", prefixedFrom(source, IdPrefix.TRANSACTION,
                "transactionId", "transaction_id", "metadata.transaction_id"));
        Payloads.putText(payload, "paymentId", prefixedFrom(source, IdPrefix.PAYMENT, "paymentId",
                "payment_id", "charge", "payment_intent"));
        Payloads.putText(payload, "merchantId", raw.merchantId());

        Instant occurredAt = occurredAtFor(eventType, source, raw);
        switch (eventType) {
            case DisputeCreated -> {
                String rawReason = Payloads.text(source, "reasonCode", "reason_code", "reason",
                        "network_reason_code");
                String reasonCode = DisputeReasonCodes.canonical(rawReason);
                if (reasonCode == null) {
                    // Reason code selects the evidence requirement profile: guessing it would
                    // corrupt readiness, gaps and the case decision. Dead-letter and extend the
                    // table instead.
                    throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                            "unmapped dispute reason code '" + rawReason
                                    + "'; extend DisputeReasonCodes and replay");
                }
                payload.put("reasonCode", reasonCode);
                Payloads.putText(payload, "networkReasonCode", Payloads.text(source,
                        "networkReasonCode", "network_reason_code", "network_reason"));
                Payloads.putMoney(payload, "disputedAmount", amount);
                Payloads.putText(payload, "status", "OPEN");
                Payloads.putInstant(payload, "deadlineAt", Payloads.instant(source, "deadlineAt",
                        "evidence_details.due_by", "respond_by", "due_by"));
                Payloads.putInstant(payload, "receivedAt", occurredAt);
            }
            case DisputeUpdated -> {
                Payloads.putText(payload, "previousStatus", upper(Payloads.text(source,
                        "previousStatus", "previous_status")));
                Payloads.putText(payload, "status", upper(Payloads.textOr(source, "EVIDENCE_GATHERING",
                        "status", "state")));
                Payloads.putInstant(payload, "deadlineAt", Payloads.instant(source, "deadlineAt",
                        "evidence_details.due_by", "due_by"));
                Payloads.putText(payload, "note", Payloads.text(source, "note", "message"));
                Payloads.putInstant(payload, "updatedAt", occurredAt);
            }
            case DisputeClosed -> {
                Payloads.putText(payload, "outcome", upper(Payloads.textOr(source, "WITHDRAWN",
                        "outcome", "status", "resolution")));
                Payloads.putMoney(payload, "recoveredAmount", Payloads.money(source, currency,
                        "recoveredAmount", "recovered_amount", "amount_recovered"));
                Payloads.putInstant(payload, "closedAt", occurredAt);
            }
            default -> throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "unreachable dispute branch for " + eventType);
        }
        return envelope(raw, eventType, disputeId, occurredAt, observedAt, payload);
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * The source instant for this fact, in preference order: the field naming the transition
     * itself, then a generic event timestamp, then the envelope's receipt time. The receipt-time
     * fallback is the documented last resort - it collapses lateness to zero, so adapters try every
     * real timestamp first.
     */
    private Instant occurredAtFor(EventType eventType, JsonNode source, RawEventEnvelope raw) {
        String[] specific = switch (eventType) {
            case PaymentCreated -> new String[]{"createdAt", "created_at", "created"};
            case PaymentAuthorized -> new String[]{"authorizedAt", "authorized_at", "authorized"};
            case PaymentCaptured -> new String[]{"capturedAt", "captured_at", "captured"};
            case PaymentFailed -> new String[]{"failedAt", "failed_at", "failed"};
            case RefundCreated -> new String[]{"createdAt", "created_at", "requestedAt", "requested_at"};
            case RefundProcessed -> new String[]{"processedAt", "processed_at", "settled_at"};
            case DisputeCreated -> new String[]{"receivedAt", "received_at", "createdAt", "created"};
            case DisputeUpdated -> new String[]{"updatedAt", "updated_at", "created"};
            case DisputeClosed -> new String[]{"closedAt", "closed_at", "resolved_at"};
            default -> new String[]{"occurredAt", "occurred_at"};
        };
        Instant fromSpecific = Payloads.instant(source, specific);
        if (fromSpecific != null) {
            return fromSpecific;
        }
        Instant fromEnvelope = Payloads.instant(raw.body(), "occurredAt", "occurred_at", "created",
                "created_at", "timestamp", "event_time");
        return fromEnvelope != null ? fromEnvelope : raw.receivedAt();
    }

    /**
     * Currency for this object's monetary fields: the source's own code when it sends one, the
     * configured fallback otherwise. Resolved once per event and threaded through every
     * {@code Payloads.money} call, so a scalar amount is never paired with the wrong currency.
     */
    private String currencyOf(JsonNode source) {
        return Payloads.normalizeCurrency(
                Payloads.text(source, "currency", "currency_code", "currencyCode"),
                defaultCurrency());
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
