package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.id.SeededIdGenerator;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.storage.Buckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Generates the synthetic financial world: merchants, customers, catalogues, transactions,
 * payments, orders, order lines, shipments, deliveries, refunds, communications, evidence and
 * disputes, spread across a configurable number of simulated days.
 *
 * <h2>Determinism</h2>
 * {@code (seed, WorldSpec)} fully determines the output, down to the bytes of every event
 * (reference section 39.11: "reproducible workloads via deterministic seeds"). Three rules keep
 * that true, and breaking any one of them silently breaks reproducibility:
 * <ol>
 *   <li><strong>One seeded generator, consumed strictly in order.</strong> Randomness comes from
 *       a {@link Random} seeded with {@code spec.seed()} - specified exactly by the JDK, so the
 *       same sequence appears on every JVM - and from {@link Ids#withSeed(long)} for identifiers.
 *       Nothing here reads {@code ThreadLocalRandom}, a hash code, or an iteration order that is
 *       not insertion order.</li>
 *   <li><strong>No wall clock.</strong> Every timestamp is an offset from
 *       {@link WorldSpec#startAt()}. {@code Instant.now()} appears nowhere in this class.</li>
 *   <li><strong>Insertion-ordered maps everywhere.</strong> Event bodies are
 *       {@link LinkedHashMap}s, so the serialised JSON is byte-identical between runs.</li>
 * </ol>
 * Generation is single-threaded per call and the class holds no state, so one bean safely serves
 * concurrent runs.
 *
 * <h2>What comes out</h2>
 * A stream of {@link SimEvent}s carrying {@link RawEventEnvelope}s in the source systems' own
 * vocabulary ({@link SourceVocabulary}), ordered by <em>observation</em> time rather than
 * occurrence time - which is what makes late arrivals genuinely late in the stream rather than
 * merely labelled as such.
 */
public class WorldGenerator {

    private static final Logger log = LoggerFactory.getLogger(WorldGenerator.class);

    private static final int SECONDS_PER_DAY = 86_400;
    /** Orders cluster in waking hours; a flat distribution across the day looks synthetic. */
    private static final int BUSINESS_START_SECOND = 7 * 3600;
    private static final int BUSINESS_END_SECOND = 23 * 3600;

    /** Generate a world. Thread-safe: all state lives in the {@link Generation} instance. */
    public GeneratedWorld generate(WorldSpec spec) {
        long startedNanos = System.nanoTime();
        GeneratedWorld world = new Generation(spec).run();
        log.info("generated world seed={} merchants={} transactions={} events={} in {} ms",
                spec.seed(), spec.merchants(), spec.transactions(), world.eventCount(),
                (System.nanoTime() - startedNanos) / 1_000_000L);
        return world;
    }

    // =======================================================================================
    // One generation pass
    // =======================================================================================

    /** A merchant in the synthetic world. */
    private record SimMerchant(String merchantId, String name, String mcc, String country,
                               String currency, List<Catalogue.Product> catalogue,
                               boolean policyExpired) {
    }

    /** A customer in the synthetic world. */
    private record SimCustomer(String customerId, String name, String email, String street,
                               String city, String country, String postalCode) {
    }

    private static final class Generation {

        private final WorldSpec spec;
        private final FailureMix mix;
        private final RandomGenerator random;
        private final SeededIdGenerator ids;

        private final List<SimEvent> events = new ArrayList<>();
        private final List<String> merchantIds = new ArrayList<>();
        private final List<String> transactionIds = new ArrayList<>();
        private final List<String> disputedTransactionIds = new ArrayList<>();
        private final List<String> evidenceIds = new ArrayList<>();
        private final Map<String, Long> counts = new LinkedHashMap<>();

        private long grossValueMinor;
        private int sequence;

        Generation(WorldSpec spec) {
            this.spec = spec;
            this.mix = spec.failureMix();
            // java.util.Random: algorithm fixed by the JDK specification, so the sequence is the
            // same on every machine. That guarantee is the whole point here.
            this.random = new Random(spec.seed());
            this.ids = Ids.withSeed(spec.seed());
        }

        GeneratedWorld run() {
            List<SimMerchant> merchants = generateMerchants();
            List<SimCustomer> customers = generateCustomers(customerCount());

            for (SimMerchant merchant : merchants) {
                generateMerchantPolicyEvidence(merchant);
            }
            for (int i = 0; i < spec.transactions(); i++) {
                SimMerchant merchant = merchants.get(i % merchants.size());
                SimCustomer customer = Catalogue.pick(random, customers);
                generateTransaction(merchant, customer);
            }

            List<SimEvent> stream = orderForEmission(events);
            stream = applyOutOfOrder(stream);
            stream = applyDuplicates(stream);
            stream = applyDrops(stream);
            stream = renumber(stream);

            counts.put(GeneratedWorld.COUNT_EVENTS, (long) stream.size());
            counts.put(GeneratedWorld.COUNT_MERCHANTS, (long) merchants.size());
            counts.put(GeneratedWorld.COUNT_CUSTOMERS, (long) customers.size());
            counts.putIfAbsent(GeneratedWorld.COUNT_TRANSACTIONS, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_EVIDENCE, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_DISPUTES, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_SHIPMENTS, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_REFUNDS, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_COMMUNICATIONS, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_LATE_EVENTS, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_DUPLICATE_EVENTS, 0L);
            counts.putIfAbsent(GeneratedWorld.COUNT_DROPPED_EVENTS, 0L);

            return new GeneratedWorld(spec, stream, merchantIds, transactionIds,
                    disputedTransactionIds, evidenceIds, counts,
                    Money.of(grossValueMinor, spec.currency()));
        }

        // -----------------------------------------------------------------------------------
        // Population
        // -----------------------------------------------------------------------------------

        private int customerCount() {
            // Roughly three transactions per customer, so repeat customers exist and
            // PRIOR_TRANSACTION_HISTORY evidence is meaningful.
            return Math.max(spec.merchants(), Math.max(4, spec.transactions() / 3));
        }

        private List<SimMerchant> generateMerchants() {
            List<SimMerchant> merchants = new ArrayList<>(spec.merchants());
            for (int i = 0; i < spec.merchants(); i++) {
                String merchantId = ids.merchant();
                String name = Catalogue.MERCHANT_NAMES.get(i % Catalogue.MERCHANT_NAMES.size());
                if (i >= Catalogue.MERCHANT_NAMES.size()) {
                    name = name + " " + (i / Catalogue.MERCHANT_NAMES.size() + 1);
                }
                String mcc = Catalogue.pick(random, Catalogue.MERCHANT_CATEGORIES);
                List<Catalogue.Product> catalogue = catalogueFor();
                boolean policyExpired = hit(mix.expiredEvidenceBps());
                merchants.add(new SimMerchant(merchantId, name, mcc, "IN", spec.currency(),
                        catalogue, policyExpired));
                merchantIds.add(merchantId);
            }
            return merchants;
        }

        /** Six to nine products drawn without replacement, so merchants differ from each other. */
        private List<Catalogue.Product> catalogueFor() {
            List<Catalogue.Product> pool = new ArrayList<>(Catalogue.PRODUCTS);
            int size = between(6, Math.min(9, pool.size()));
            List<Catalogue.Product> chosen = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                chosen.add(pool.remove(random.nextInt(pool.size())));
            }
            return List.copyOf(chosen);
        }

        private List<SimCustomer> generateCustomers(int count) {
            List<SimCustomer> customers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String first = Catalogue.pick(random, Catalogue.FIRST_NAMES);
                String last = Catalogue.pick(random, Catalogue.LAST_NAMES);
                String name = first + " " + last;
                String email = (first + "." + last).toLowerCase(Locale.ROOT) + i + "@example.com";
                String street = between(1, 240) + " " + Catalogue.pick(random, Catalogue.STREETS);
                String city = Catalogue.pick(random, Catalogue.CITIES);
                String postalCode = String.format(Locale.ROOT, "%06d", between(110001, 700099));
                customers.add(new SimCustomer(ids.customer(), name, email, street, city, "IN",
                        postalCode));
            }
            return customers;
        }

        /**
         * Merchant-level evidence: the refund policy and the terms of service. Both are
         * versioned documents with an expiry, and an expired one is the cheapest realistic way
         * to produce an EXPIRED readiness gap without touching any transaction.
         */
        private void generateMerchantPolicyEvidence(SimMerchant merchant) {
            String correlationId = ids.eventId();
            Instant publishedAt = spec.startAt().minus(Duration.ofDays(30));
            Instant expiresAt = merchant.policyExpired()
                    ? spec.startAt().minus(Duration.ofDays(5))
                    : spec.startAt().plus(Duration.ofDays(365));

            emitEvidence(merchant, null, null, EvidenceType.MERCHANT_POLICY,
                    merchant.name() + " refund and delivery policy",
                    "Refund window 30 days from delivery. Proof of delivery required for all "
                            + "goods-not-received claims.",
                    AggregateType.MERCHANT, merchant.merchantId(), correlationId,
                    publishedAt, publishedAt, expiresAt, EvidenceSource.MERCHANT_PORTAL, null,
                    fields(
                            "policyVersion", "3",
                            "refundWindowDays", "30",
                            "deliveryProofRequired", "true",
                            "publishedAt", publishedAt.toString(),
                            "expiresAt", expiresAt.toString()),
                    null);

            emitEvidence(merchant, null, null, EvidenceType.TERMS_OF_SERVICE,
                    merchant.name() + " terms of service",
                    "Subscription renewals occur automatically unless cancelled at least 48 hours "
                            + "before the renewal date.",
                    AggregateType.MERCHANT, merchant.merchantId(), correlationId,
                    publishedAt, publishedAt, expiresAt, EvidenceSource.MERCHANT_PORTAL, null,
                    fields(
                            "termsVersion", "7",
                            "cancellationNoticeHours", "48",
                            "governingLaw", "IN",
                            "expiresAt", expiresAt.toString()),
                    null);
        }

        // -----------------------------------------------------------------------------------
        // One transaction, from order to dispute
        // -----------------------------------------------------------------------------------

        private void generateTransaction(SimMerchant merchant, SimCustomer customer) {
            String transactionId = ids.transaction();
            String orderId = ids.order();
            String paymentId = ids.payment();
            String correlationId = ids.eventId();
            transactionIds.add(transactionId);
            bump(GeneratedWorld.COUNT_TRANSACTIONS);

            List<Map<String, Object>> lines = new ArrayList<>();
            long totalMinor = 0;
            int lineCount = between(1, 3);
            for (int i = 0; i < lineCount; i++) {
                Catalogue.Product product = Catalogue.pick(random, merchant.catalogue());
                int quantity = between(1, 3);
                long lineMinor = product.unitAmountMinor() * quantity;
                totalMinor += lineMinor;
                lines.add(orderedMap(
                        "lineId", orderId + "-L" + (i + 1),
                        "sku", product.sku(),
                        "description", product.name(),
                        "quantity", quantity,
                        "unitAmount", money(product.unitAmountMinor()),
                        "lineAmount", money(lineMinor)));
            }
            if (spec.minAmountMinor() > totalMinor) {
                // High-value scenarios need a guaranteed floor, and a random catalogue draw cannot
                // provide one. A single top-up line is deterministic, keeps the arithmetic in
                // minor units, and reads plausibly on an invoice.
                long topUpMinor = spec.minAmountMinor() - totalMinor;
                totalMinor += topUpMinor;
                lines.add(orderedMap(
                        "lineId", orderId + "-L" + (lines.size() + 1),
                        "sku", "SVC-9001",
                        "description", "Extended warranty and priority handling",
                        "quantity", 1,
                        "unitAmount", money(topUpMinor),
                        "lineAmount", money(topUpMinor)));
            }
            grossValueMinor += totalMinor;
            final long amountMinor = totalMinor;

            Instant orderedAt = orderInstant();

            emit(EventType.OrderCreated, AggregateType.ORDER, orderId, merchant, transactionId,
                    correlationId, orderedAt, null, orderedMap(
                            "orderId", orderId,
                            "transactionId", transactionId,
                            "merchantId", merchant.merchantId(),
                            "customerId", customer.customerId(),
                            "customerName", customer.name(),
                            "customerEmail", customer.email(),
                            "placedAt", orderedAt.toString(),
                            "currency", spec.currency(),
                            "totalAmount", money(amountMinor),
                            "lines", lines,
                            "shippingAddress", address(customer)), null);

            emit(EventType.PaymentCreated, AggregateType.PAYMENT, paymentId, merchant, transactionId,
                    correlationId, orderedAt.plusSeconds(5), null,
                    paymentBody(paymentId, orderId, transactionId, merchant, customer, amountMinor,
                            "REQUIRES_CONFIRMATION", orderedAt.plusSeconds(5), null), null);

            emit(EventType.PaymentAuthorized, AggregateType.PAYMENT, paymentId, merchant, transactionId,
                    correlationId, orderedAt.plusSeconds(17), null,
                    paymentBody(paymentId, orderId, transactionId, merchant, customer, amountMinor,
                            "AUTHORIZED", orderedAt.plusSeconds(5), null), null);

            if (hit(mix.paymentFailureBps())) {
                emit(EventType.PaymentFailed, AggregateType.PAYMENT, paymentId, merchant, transactionId,
                        correlationId, orderedAt.plusSeconds(23), null,
                        paymentBody(paymentId, orderId, transactionId, merchant, customer, amountMinor,
                                "FAILED", orderedAt.plusSeconds(5), null), null);
                emit(EventType.OrderCancelled, AggregateType.ORDER, orderId, merchant, transactionId,
                        correlationId, orderedAt.plusSeconds(3600), null, orderedMap(
                                "orderId", orderId,
                                "transactionId", transactionId,
                                "cancelledAt", orderedAt.plusSeconds(3600).toString(),
                                "reason", "PAYMENT_FAILED"), null);
                return;
            }

            Instant capturedAt = orderedAt.plusSeconds(41);
            emit(EventType.PaymentCaptured, AggregateType.PAYMENT, paymentId, merchant, transactionId,
                    correlationId, capturedAt, null,
                    paymentBody(paymentId, orderId, transactionId, merchant, customer, amountMinor,
                            "CAPTURED", orderedAt.plusSeconds(5), capturedAt), null);

            generatePaymentEvidence(merchant, customer, transactionId, orderId, paymentId,
                    correlationId, amountMinor, capturedAt, lines);

            Instant lastDeliveredAt = generateShipments(merchant, customer, transactionId, orderId,
                    correlationId, orderedAt, amountMinor);

            if (lastDeliveredAt != null) {
                emit(EventType.OrderFulfilled, AggregateType.ORDER, orderId, merchant, transactionId,
                        correlationId, lastDeliveredAt.plus(Duration.ofHours(2)), null, orderedMap(
                                "orderId", orderId,
                                "transactionId", transactionId,
                                "fulfilledAt", lastDeliveredAt.plus(Duration.ofHours(2)).toString()),
                        null);
            }

            Instant anchor = lastDeliveredAt == null ? capturedAt.plus(Duration.ofDays(5)) : lastDeliveredAt;

            if (hit(mix.customerContactBps())) {
                generateInboundCommunication(merchant, customer, transactionId, orderId,
                        correlationId, anchor);
            }

            long refundedMinor = 0;
            boolean partialRefund = false;
            if (hit(mix.refundBps())) {
                partialRefund = hit(mix.partialRefundBps());
                refundedMinor = generateRefund(merchant, customer, transactionId, paymentId,
                        correlationId, anchor, amountMinor, partialRefund);
            }

            if (hit(spec.disputeRateBps())) {
                generateDispute(merchant, customer, transactionId, orderId, paymentId, correlationId,
                        anchor, amountMinor, refundedMinor, partialRefund, lastDeliveredAt);
            }
        }

        private void generatePaymentEvidence(SimMerchant merchant, SimCustomer customer,
                                             String transactionId, String orderId, String paymentId,
                                             String correlationId, long amountMinor,
                                             Instant capturedAt, List<Map<String, Object>> lines) {
            emitEvidence(merchant, transactionId, customer.customerId(), EvidenceType.PAYMENT_PROOF,
                    "Payment capture receipt " + paymentId,
                    "Card payment captured for transaction " + transactionId,
                    AggregateType.PAYMENT, paymentId, correlationId,
                    capturedAt.plus(Duration.ofMinutes(2)), capturedAt, null,
                    EvidenceSource.PSP_ADAPTER, amountMinor,
                    fields(
                            "paymentId", paymentId,
                            "transactionId", transactionId,
                            "capturedAt", capturedAt.toString(),
                            "amountMinor", Long.toString(amountMinor),
                            "currency", spec.currency(),
                            "processor", "pdei-psp-sim",
                            "authorizationCode", authCode()),
                    null);

            StringBuilder lineSummary = new StringBuilder();
            for (Map<String, Object> line : lines) {
                lineSummary.append(line.get("quantity")).append(" x ")
                        .append(line.get("description")).append("; ");
            }
            emitEvidence(merchant, transactionId, customer.customerId(), EvidenceType.INVOICE,
                    "Invoice for order " + orderId,
                    "Itemised invoice: " + lineSummary,
                    AggregateType.ORDER, orderId, correlationId,
                    capturedAt.plus(Duration.ofMinutes(5)), capturedAt, null,
                    EvidenceSource.ORDER_SYSTEM, amountMinor,
                    fields(
                            "orderId", orderId,
                            "invoiceNumber", "INV-" + orderId.substring(4),
                            "billedTo", customer.name(),
                            "billingEmail", customer.email(),
                            "totalAmountMinor", Long.toString(amountMinor),
                            "currency", spec.currency(),
                            "lineItems", lineSummary.toString()),
                    null);

            emitEvidence(merchant, transactionId, customer.customerId(), EvidenceType.ORDER_RECORD,
                    "Order record " + orderId,
                    "Order placed by " + customer.name() + " and shipped to " + customer.city(),
                    AggregateType.ORDER, orderId, correlationId,
                    capturedAt.plus(Duration.ofMinutes(7)), capturedAt, null,
                    EvidenceSource.ORDER_SYSTEM, amountMinor,
                    fields(
                            "orderId", orderId,
                            "customerName", customer.name(),
                            "shippingAddress", customer.street() + ", " + customer.city() + " "
                                    + customer.postalCode(),
                            "itemCount", Integer.toString(lines.size())),
                    null);

            emitEvidence(merchant, transactionId, customer.customerId(), EvidenceType.AVS_CVV_RESULT,
                    "AVS and CVV result for " + paymentId,
                    "Address and card verification results at authorization time",
                    AggregateType.PAYMENT, paymentId, correlationId,
                    capturedAt.plus(Duration.ofMinutes(1)), capturedAt, null,
                    EvidenceSource.PSP_ADAPTER, null,
                    fields(
                            "avsResult", "Y",
                            "cvvResult", "M",
                            "cardBrand", "VISA",
                            "cardLast4", cardLast4(),
                            "checkedAt", capturedAt.toString()),
                    null);
        }

        /**
         * Shipments and deliveries. This is where most readiness gaps come from, so three
         * failure shapes live here: a missing delivery proof, two delivery proofs that disagree,
         * and an order split across shipments where one never arrives.
         *
         * @return the latest delivery instant, or null when nothing was delivered
         */
        private Instant generateShipments(SimMerchant merchant, SimCustomer customer,
                                          String transactionId, String orderId, String correlationId,
                                          Instant orderedAt, long amountMinor) {
            boolean multiShipment = hit(mix.multiShipmentBps());
            int shipmentCount = multiShipment ? between(2, 3) : 1;
            boolean missingProof = hit(mix.missingDeliveryProofBps());
            boolean contradictory = hit(mix.contradictoryDeliveryBps());
            // In a split order, one parcel is still in transit - the classic partial-delivery gap.
            int strandedIndex = multiShipment ? random.nextInt(shipmentCount) : -1;

            Instant lastDeliveredAt = null;

            for (int s = 0; s < shipmentCount; s++) {
                String shipmentId = ids.shipment();
                String carrier = Catalogue.pick(random, Catalogue.CARRIERS);
                String tracking = trackingNumber(carrier);
                bump(GeneratedWorld.COUNT_SHIPMENTS);

                Instant createdAt = orderedAt.plus(Duration.ofHours(between(4, 20) + 12L * s));
                emit(EventType.ShipmentCreated, AggregateType.SHIPMENT, shipmentId, merchant,
                        transactionId, correlationId, createdAt, null,
                        shipmentBody(shipmentId, orderId, transactionId, merchant, customer, carrier,
                                tracking, "LABEL_CREATED", createdAt, null, null), null);

                emitEvidence(merchant, transactionId, customer.customerId(),
                        EvidenceType.SHIPPING_RECORD,
                        "Shipping record " + tracking,
                        carrier + " consignment " + tracking + " for order " + orderId,
                        AggregateType.SHIPMENT, shipmentId, correlationId,
                        createdAt.plus(Duration.ofMinutes(15)), createdAt, null,
                        EvidenceSource.LOGISTICS, null,
                        fields(
                                "shipmentId", shipmentId,
                                "carrier", carrier,
                                "trackingNumber", tracking,
                                "orderId", orderId,
                                "parcel", (s + 1) + " of " + shipmentCount,
                                "createdAt", createdAt.toString()),
                        null);

                Instant dispatchedAt = createdAt.plus(Duration.ofHours(between(4, 20)));
                emit(EventType.ShipmentDispatched, AggregateType.SHIPMENT, shipmentId, merchant,
                        transactionId, correlationId, dispatchedAt, null,
                        shipmentBody(shipmentId, orderId, transactionId, merchant, customer, carrier,
                                tracking, "IN_TRANSIT", createdAt, dispatchedAt, null), null);

                generateDispatchNotice(merchant, customer, transactionId, orderId, correlationId,
                        carrier, tracking, dispatchedAt);

                if (s == strandedIndex) {
                    continue; // still in transit when the dispute lands
                }

                Instant deliveredAt = dispatchedAt.plus(Duration.ofHours(between(20, 96)));
                emit(EventType.ShipmentDelivered, AggregateType.SHIPMENT, shipmentId, merchant,
                        transactionId, correlationId, deliveredAt, null,
                        shipmentBody(shipmentId, orderId, transactionId, merchant, customer, carrier,
                                tracking, "DELIVERED", createdAt, dispatchedAt, deliveredAt), null);
                lastDeliveredAt = deliveredAt;

                if (missingProof) {
                    // Carrier says delivered, but nobody ever uploaded the signed proof: the
                    // single most common reason a defendable dispute is lost.
                    continue;
                }

                emitDeliveryProof(merchant, customer, transactionId, orderId, shipmentId,
                        correlationId, carrier, tracking, deliveredAt, s + 1, shipmentCount,
                        EvidenceSource.LOGISTICS, "carrier scan");

                if (contradictory) {
                    // A second delivery record from the merchant portal, dated SIX HOURS BEFORE
                    // dispatch. The impossible ordering is the point: ContradictionDetector keys
                    // off deliveredAt preceding dispatchedAt, so this produces a real
                    // CONTRADICTORY gap rather than two artifacts that merely look different.
                    Instant impossible = dispatchedAt.minus(Duration.ofHours(6));
                    emitDeliveryProof(merchant, customer, transactionId, orderId, shipmentId,
                            correlationId, carrier, tracking, impossible, s + 1, shipmentCount,
                            EvidenceSource.MERCHANT_PORTAL, "merchant portal upload");
                }
            }
            return lastDeliveredAt;
        }

        private void emitDeliveryProof(SimMerchant merchant, SimCustomer customer, String transactionId,
                                       String orderId, String shipmentId, String correlationId,
                                       String carrier, String tracking, Instant deliveredAt,
                                       int parcel, int parcels, EvidenceSource source, String provenance) {
            String deliveryId = ids.delivery();
            emitEvidence(merchant, transactionId, customer.customerId(), EvidenceType.DELIVERY_PROOF,
                    "Proof of delivery " + tracking,
                    "Parcel " + parcel + " of " + parcels + " delivered to " + customer.name()
                            + " at " + customer.street() + ", " + customer.city(),
                    AggregateType.DELIVERY, deliveryId, correlationId,
                    deliveredAt.plus(Duration.ofMinutes(10)), deliveredAt, null, source, null,
                    deliveryProofFields(deliveryId, shipmentId, orderId, carrier, tracking,
                            deliveredAt, customer, provenance),
                    deliveryProofLag());
        }

        /**
         * Forced lag for a delivery proof, or null for normal timing.
         *
         * <p>{@code deliveryProofLateDays} is how the late-evidence scenario is built: the parcel
         * arrives on time, the signed proof is uploaded weeks later, after the dispute has already
         * been opened. Readiness therefore recomputes from a gap to a clean score, which is the
         * behaviour "assume late and out-of-order events" is supposed to guarantee.
         */
        private Duration deliveryProofLag() {
            return mix.deliveryProofLateDays() > 0
                    ? Duration.ofDays(mix.deliveryProofLateDays())
                    : null;
        }

        private Map<String, String> deliveryProofFields(String deliveryId, String shipmentId,
                                                        String orderId, String carrier, String tracking,
                                                        Instant deliveredAt, SimCustomer customer,
                                                        String provenance) {
            return fields(
                    "deliveryId", deliveryId,
                    "shipmentId", shipmentId,
                    "orderId", orderId,
                    "carrier", carrier,
                    "trackingNumber", tracking,
                    // ContradictionDetector compares this against the shipment's dispatchedAt and
                    // the order's createdAt; a deliveredAt that precedes either is a HIGH-severity
                    // CONTRADICTORY gap.
                    "deliveredAt", deliveredAt.toString(),
                    "recipientName", customer.name(),
                    "deliveryAddress", customer.street() + ", " + customer.city() + " "
                            + customer.postalCode(),
                    "signature", "SIGNED",
                    "provenance", provenance);
        }

        private void generateDispatchNotice(SimMerchant merchant, SimCustomer customer,
                                            String transactionId, String orderId, String correlationId,
                                            String carrier, String tracking, Instant dispatchedAt) {
            String communicationId = ids.communication();
            bump(GeneratedWorld.COUNT_COMMUNICATIONS);
            Instant sentAt = dispatchedAt.plus(Duration.ofMinutes(5));
            emit(EventType.CommunicationCreated, AggregateType.COMMUNICATION, communicationId,
                    merchant, transactionId, correlationId, sentAt, null, orderedMap(
                            "communicationId", communicationId,
                            "transactionId", transactionId,
                            "orderId", orderId,
                            "customerId", customer.customerId(),
                            "direction", "OUTBOUND",
                            "channel", "EMAIL",
                            "sender", "orders@" + slug(merchant.name()) + ".example",
                            "recipient", customer.email(),
                            "subject", "Your order " + orderId + " is on its way",
                            "body", "Hello " + customer.name() + ", your order " + orderId
                                    + " has been dispatched with " + carrier
                                    + ". Tracking number " + tracking + ".",
                            "sentAt", sentAt.toString()), null);
        }

        private void generateInboundCommunication(SimMerchant merchant, SimCustomer customer,
                                                  String transactionId, String orderId,
                                                  String correlationId, Instant anchor) {
            String communicationId = ids.communication();
            bump(GeneratedWorld.COUNT_COMMUNICATIONS);
            Instant receivedAt = anchor.plus(Duration.ofHours(between(6, 72)));
            String subject = "Re: Order " + orderId;
            String body = "Hello, confirming the parcel for order " + orderId + " arrived on "
                    + receivedAt.toString().substring(0, 10)
                    + " and was received in good condition. Thank you.";

            emit(EventType.CommunicationReceived, AggregateType.COMMUNICATION, communicationId,
                    merchant, transactionId, correlationId, receivedAt, null, orderedMap(
                            "communicationId", communicationId,
                            "transactionId", transactionId,
                            "orderId", orderId,
                            "customerId", customer.customerId(),
                            "direction", "INBOUND",
                            "channel", Catalogue.pick(random, Catalogue.COMMUNICATION_CHANNELS),
                            "sender", customer.email(),
                            "recipient", "support@" + slug(merchant.name()) + ".example",
                            "subject", subject,
                            "body", body,
                            "receivedAt", receivedAt.toString()), null);

            emitEvidence(merchant, transactionId, customer.customerId(),
                    EvidenceType.CUSTOMER_COMMUNICATION, subject,
                    "Inbound customer message about order " + orderId,
                    AggregateType.COMMUNICATION, communicationId, correlationId,
                    receivedAt.plus(Duration.ofMinutes(2)), receivedAt, null,
                    EvidenceSource.CRM, null,
                    fields(
                            "communicationId", communicationId,
                            "direction", "INBOUND",
                            "from", customer.email(),
                            "to", "support@" + slug(merchant.name()) + ".example",
                            "date", receivedAt.toString(),
                            "subject", subject,
                            "body", body),
                    null);
        }

        /** @return the refunded amount in minor units */
        private long generateRefund(SimMerchant merchant, SimCustomer customer, String transactionId,
                                    String paymentId, String correlationId, Instant anchor,
                                    long amountMinor, boolean partial) {
            String refundId = ids.refund();
            bump(GeneratedWorld.COUNT_REFUNDS);
            // Integer arithmetic only: a percentage of minor units, never a float multiply.
            long refundMinor = partial ? amountMinor * between(30, 70) / 100 : amountMinor;
            Instant createdAt = anchor.plus(Duration.ofDays(between(2, 12)));
            Instant processedAt = createdAt.plus(Duration.ofDays(between(1, 4)));

            emit(EventType.RefundCreated, AggregateType.REFUND, refundId, merchant, transactionId,
                    correlationId, createdAt, null,
                    refundBody(refundId, paymentId, transactionId, refundMinor, amountMinor, partial,
                            "PENDING", createdAt, null), null);

            emit(EventType.RefundProcessed, AggregateType.REFUND, refundId, merchant, transactionId,
                    correlationId, processedAt, null,
                    refundBody(refundId, paymentId, transactionId, refundMinor, amountMinor, partial,
                            "SUCCEEDED", createdAt, processedAt), null);

            emitEvidence(merchant, transactionId, customer.customerId(), EvidenceType.REFUND_RECEIPT,
                    "Refund receipt " + refundId,
                    (partial ? "Partial" : "Full") + " refund issued against payment " + paymentId,
                    AggregateType.REFUND, refundId, correlationId,
                    processedAt.plus(Duration.ofMinutes(5)), processedAt, null,
                    EvidenceSource.PSP_ADAPTER, refundMinor,
                    fields(
                            "refundId", refundId,
                            "paymentId", paymentId,
                            "partial", Boolean.toString(partial),
                            "refundedAmountMinor", Long.toString(refundMinor),
                            "originalAmountMinor", Long.toString(amountMinor),
                            "currency", spec.currency(),
                            "processedAt", processedAt.toString()),
                    null);
            return refundMinor;
        }

        private void generateDispute(SimMerchant merchant, SimCustomer customer, String transactionId,
                                     String orderId, String paymentId, String correlationId,
                                     Instant anchor, long amountMinor, long refundedMinor,
                                     boolean partialRefund, Instant lastDeliveredAt) {
            String disputeId = ids.dispute();
            disputedTransactionIds.add(transactionId);
            bump(GeneratedWorld.COUNT_DISPUTES);

            DisputeReasonCode reasonCode = spec.forcedReasonCode() != null
                    ? spec.forcedReasonCode()
                    : chooseReasonCode(lastDeliveredAt, refundedMinor, partialRefund);
            long disputedMinor = reasonCode == DisputeReasonCode.CREDIT_NOT_PROCESSED && refundedMinor > 0
                    ? amountMinor - refundedMinor
                    : amountMinor;
            Instant openedAt = anchor.plus(Duration.ofDays(between(8, 45)));
            // A pinned deadline is how the urgent scenario is built: deadlineUrgency is 1.0 inside
            // 48 hours (platform contract 9.4), which is what pushes a case over the admission
            // threshold before a calmer one with the same evidence gaps.
            int deadlineDays = spec.disputeDeadlineDays() > 0
                    ? spec.disputeDeadlineDays()
                    : between(7, 21);
            Instant dueBy = openedAt.plus(Duration.ofDays(deadlineDays));

            emit(EventType.DisputeCreated, AggregateType.DISPUTE, disputeId, merchant, transactionId,
                    correlationId, openedAt, null, orderedMap(
                            "disputeId", disputeId,
                            "transactionId", transactionId,
                            "orderId", orderId,
                            "paymentId", paymentId,
                            "merchantId", merchant.merchantId(),
                            "customerId", customer.customerId(),
                            "reasonCode", reasonCode.name(),
                            "amount", money(Math.max(0, disputedMinor)),
                            "status", "OPEN",
                            "network", "VISA",
                            "caseNumber", "CB" + between(1000000, 9999999),
                            "openedAt", openedAt.toString(),
                            "evidenceDueBy", dueBy.toString()), null);

            // A disputed transaction is worth defending, so the merchant pulls the customer's
            // history: PRIOR_TRANSACTION_HISTORY is RECOMMENDED for most reason codes.
            emitEvidence(merchant, transactionId, customer.customerId(),
                    EvidenceType.PRIOR_TRANSACTION_HISTORY,
                    "Prior transaction history for " + customer.name(),
                    "Previous successful, undisputed orders placed by this customer",
                    AggregateType.CUSTOMER, customer.customerId(), correlationId,
                    openedAt.plus(Duration.ofHours(3)), openedAt, null,
                    EvidenceSource.INTERNAL_DERIVED, null,
                    fields(
                            "customerId", customer.customerId(),
                            "priorOrders", Integer.toString(between(1, 14)),
                            "priorDisputes", "0",
                            "firstSeenAt", spec.startAt().minus(Duration.ofDays(between(60, 900)))
                                    .toString(),
                            "compiledAt", openedAt.toString()),
                    null);
        }

        /** Picks a reason code that is consistent with what actually happened. */
        private DisputeReasonCode chooseReasonCode(Instant lastDeliveredAt, long refundedMinor,
                                                   boolean partialRefund) {
            if (lastDeliveredAt == null) {
                return DisputeReasonCode.GOODS_NOT_RECEIVED;
            }
            if (refundedMinor > 0 && partialRefund) {
                return DisputeReasonCode.CREDIT_NOT_PROCESSED;
            }
            List<DisputeReasonCode> plausible = List.of(
                    DisputeReasonCode.GOODS_NOT_RECEIVED,
                    DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED,
                    DisputeReasonCode.FRAUDULENT_TRANSACTION,
                    DisputeReasonCode.UNRECOGNIZED_TRANSACTION,
                    DisputeReasonCode.DUPLICATE_PROCESSING,
                    DisputeReasonCode.SUBSCRIPTION_CANCELLED,
                    DisputeReasonCode.INCORRECT_AMOUNT);
            return Catalogue.pick(random, plausible);
        }

        // -----------------------------------------------------------------------------------
        // Event bodies
        // -----------------------------------------------------------------------------------

        private Map<String, Object> paymentBody(String paymentId, String orderId, String transactionId,
                                                SimMerchant merchant, SimCustomer customer,
                                                long amountMinor, String status, Instant createdAt,
                                                Instant capturedAt) {
            return orderedMap(
                    "paymentId", paymentId,
                    "orderId", orderId,
                    "transactionId", transactionId,
                    "merchantId", merchant.merchantId(),
                    "customerId", customer.customerId(),
                    "amount", money(amountMinor),
                    "status", status,
                    "processor", "pdei-psp-sim",
                    "paymentMethod", "CARD",
                    "cardBrand", "VISA",
                    "cardLast4", cardLast4(),
                    "authorizationCode", authCode(),
                    "avsResult", "Y",
                    "cvvResult", "M",
                    "createdAt", createdAt.toString(),
                    "capturedAt", capturedAt == null ? null : capturedAt.toString());
        }

        private Map<String, Object> shipmentBody(String shipmentId, String orderId, String transactionId,
                                                 SimMerchant merchant, SimCustomer customer,
                                                 String carrier, String tracking, String status,
                                                 Instant createdAt, Instant dispatchedAt,
                                                 Instant deliveredAt) {
            return orderedMap(
                    "shipmentId", shipmentId,
                    "orderId", orderId,
                    "transactionId", transactionId,
                    "merchantId", merchant.merchantId(),
                    "carrier", carrier,
                    "trackingNumber", tracking,
                    "status", status,
                    "createdAt", createdAt.toString(),
                    "dispatchedAt", dispatchedAt == null ? null : dispatchedAt.toString(),
                    "deliveredAt", deliveredAt == null ? null : deliveredAt.toString(),
                    "recipientName", customer.name(),
                    "deliveryAddress", address(customer));
        }

        private Map<String, Object> refundBody(String refundId, String paymentId, String transactionId,
                                               long refundMinor, long originalMinor, boolean partial,
                                               String status, Instant createdAt, Instant processedAt) {
            return orderedMap(
                    "refundId", refundId,
                    "paymentId", paymentId,
                    "transactionId", transactionId,
                    "amount", money(refundMinor),
                    "originalAmount", money(originalMinor),
                    "partial", partial,
                    "status", status,
                    "reason", partial ? "PARTIAL_RETURN" : "CUSTOMER_REQUEST",
                    "createdAt", createdAt.toString(),
                    "processedAt", processedAt == null ? null : processedAt.toString());
        }

        private Map<String, Object> address(SimCustomer customer) {
            return orderedMap(
                    "line1", customer.street(),
                    "city", customer.city(),
                    "postalCode", customer.postalCode(),
                    "country", customer.country());
        }

        /** Money on the wire is always {@code {amountMinor, currency}} - never a decimal. */
        private Map<String, Object> money(long amountMinor) {
            return orderedMap("amountMinor", amountMinor, "currency", spec.currency());
        }

        // -----------------------------------------------------------------------------------
        // Emission
        // -----------------------------------------------------------------------------------

        /**
         * Emits an {@code EvidenceAdded} raw event together with the synthetic bytes behind it,
         * so the artifact really exists in MinIO and really hashes to the recorded sha256.
         */
        private void emitEvidence(SimMerchant merchant, String transactionId, String customerId,
                                  EvidenceType type, String title, String summary,
                                  AggregateType relatedType, String relatedId, String correlationId,
                                  Instant uploadedAt, Instant capturedAt, Instant expiresAt,
                                  EvidenceSource source, Long amountMinor,
                                  Map<String, String> documentFields, Duration forcedLag) {
            String evidenceId = ids.evidence();
            evidenceIds.add(evidenceId);
            bump(GeneratedWorld.COUNT_EVIDENCE);

            String artifactTransactionId = transactionId == null ? merchant.merchantId() : transactionId;
            SyntheticArtifact artifact = SyntheticArtifact.render(evidenceId, merchant.merchantId(),
                    artifactTransactionId, type, title, documentFields);

            Map<String, Object> body = orderedMap(
                    "documentId", evidenceId,
                    "merchantId", merchant.merchantId(),
                    "transactionId", transactionId,
                    "customerId", customerId,
                    "documentType", type.name(),
                    "relatedEntityType", relatedType.name(),
                    "relatedEntityId", relatedId,
                    "title", title,
                    "summary", summary,
                    "source", source.name(),
                    "bucket", Buckets.EVIDENCE,
                    "objectKey", artifact.objectKey(),
                    "filename", artifact.filename(),
                    "contentType", artifact.contentType(),
                    "sizeBytes", artifact.sizeBytes(),
                    "sha256", artifact.sha256(),
                    "capturedAt", capturedAt.toString(),
                    "effectiveFrom", capturedAt.toString(),
                    "expiresAt", expiresAt == null ? null : expiresAt.toString(),
                    "amount", amountMinor == null ? null : money(amountMinor),
                    "attributes", new LinkedHashMap<String, Object>(documentFields));

            emit(EventType.EvidenceAdded, AggregateType.EVIDENCE, evidenceId, merchant,
                    transactionId, correlationId, uploadedAt, forcedLag, body, artifact);
        }

        /**
         * Appends one event to the stream.
         *
         * <p>{@code observedAt} is where late arrival is decided: normally a few seconds after
         * the fact, but with probability {@code lateEventBps} (or whenever {@code forcedLag} is
         * supplied) days later. Since the stream is finally ordered by observation time, a late
         * event genuinely arrives out of sequence rather than merely carrying a stale timestamp.
         */
        private void emit(EventType type, AggregateType aggregateType, String aggregateId,
                          SimMerchant merchant, String transactionId, String correlationId,
                          Instant occurredAt, Duration forcedLag, Map<String, Object> body,
                          SyntheticArtifact artifact) {
            Duration lag = forcedLag;
            if (lag == null) {
                lag = hit(mix.lateEventBps())
                        ? Duration.ofHours(between(26, 120))
                        : Duration.ofSeconds(between(1, 9));
            }
            Instant observedAt = occurredAt.plus(lag);
            if (lag.toMinutes() > 1) {
                bump(GeneratedWorld.COUNT_LATE_EVENTS);
            }

            String sourceSystem = SourceVocabulary.systemFor(type);
            String sourceEventType = SourceVocabulary.sourceEventType(type);
            String idempotencyKey = sourceSystem + ":" + sourceEventType + ":" + aggregateId
                    + ":" + occurredAt.toEpochMilli();

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(EventHeaders.EVENT_TYPE, type.name());
            headers.put(EventHeaders.MERCHANT_ID, merchant.merchantId());
            headers.put(EventHeaders.CORRELATION_ID, correlationId);
            headers.put(EventHeaders.SCHEMA_VERSION, "1");
            headers.put(SIM_SEED_HEADER, Long.toString(spec.seed()));
            headers.put(SIM_OCCURRED_AT_HEADER, occurredAt.toString());

            RawEventEnvelope envelope = new RawEventEnvelope(ids.eventId(), sourceSystem,
                    sourceEventType, merchant.merchantId(), observedAt, idempotencyKey, headers,
                    Json.tree(body));

            events.add(new SimEvent(sequence++, occurredAt, observedAt, type, aggregateType,
                    aggregateId, merchant.merchantId(), transactionId, envelope, artifact));
        }

        // -----------------------------------------------------------------------------------
        // Stream shaping
        // -----------------------------------------------------------------------------------

        /** PDEI sees events in the order they were observed, not the order they happened. */
        private List<SimEvent> orderForEmission(List<SimEvent> source) {
            List<SimEvent> ordered = new ArrayList<>(source);
            ordered.sort(Comparator.comparing(SimEvent::observedAt)
                    .thenComparingInt(SimEvent::sequence));
            return ordered;
        }

        /** Swaps adjacent pairs so consumers must tolerate genuinely out-of-order delivery. */
        private List<SimEvent> applyOutOfOrder(List<SimEvent> source) {
            if (mix.outOfOrderBps() == 0 || source.size() < 2) {
                return source;
            }
            List<SimEvent> shuffled = new ArrayList<>(source);
            for (int i = 0; i < shuffled.size() - 1; i++) {
                if (hit(mix.outOfOrderBps())) {
                    SimEvent a = shuffled.get(i);
                    shuffled.set(i, shuffled.get(i + 1));
                    shuffled.set(i + 1, a);
                    i++; // do not immediately swap the same element back
                }
            }
            return shuffled;
        }

        /**
         * Re-emits events verbatim - same {@code rawEventId}, same {@code idempotencyKey} - which
         * is what a redelivering webhook actually does, and what every consumer must survive.
         */
        private List<SimEvent> applyDuplicates(List<SimEvent> source) {
            if (mix.duplicateEventBps() == 0) {
                return source;
            }
            List<SimEvent> withDuplicates = new ArrayList<>(source.size() + 16);
            long duplicated = 0;
            for (SimEvent event : source) {
                withDuplicates.add(event);
                if (hit(mix.duplicateEventBps())) {
                    withDuplicates.add(event);
                    duplicated++;
                }
            }
            counts.put(GeneratedWorld.COUNT_DUPLICATE_EVENTS, duplicated);
            return withDuplicates;
        }

        /**
         * Silently drops a few events.
         *
         * <p>Restricted to evidence and communication events on purpose: dropping a
         * {@code PaymentCaptured} would leave the ledger wrong, which is a different (and much
         * less interesting) failure than "the document nobody uploaded". The gap this produces is
         * exactly what GapDetector is for.
         */
        private List<SimEvent> applyDrops(List<SimEvent> source) {
            if (mix.droppedEventBps() == 0) {
                return source;
            }
            List<SimEvent> kept = new ArrayList<>(source.size());
            long dropped = 0;
            for (SimEvent event : source) {
                boolean droppable = event.isEvidence()
                        || event.canonicalType() == EventType.CommunicationCreated
                        || event.canonicalType() == EventType.CommunicationReceived;
                if (droppable && hit(mix.droppedEventBps())) {
                    dropped++;
                    continue;
                }
                kept.add(event);
            }
            counts.put(GeneratedWorld.COUNT_DROPPED_EVENTS, dropped);
            return kept;
        }

        private List<SimEvent> renumber(List<SimEvent> source) {
            List<SimEvent> renumbered = new ArrayList<>(source.size());
            for (int i = 0; i < source.size(); i++) {
                renumbered.add(source.get(i).withSequence(i));
            }
            return List.copyOf(renumbered);
        }

        // -----------------------------------------------------------------------------------
        // Small deterministic helpers
        // -----------------------------------------------------------------------------------

        private Instant orderInstant() {
            int day = random.nextInt(spec.days());
            int second = BUSINESS_START_SECOND
                    + random.nextInt(BUSINESS_END_SECOND - BUSINESS_START_SECOND);
            return spec.startAt().plusSeconds((long) day * SECONDS_PER_DAY + second);
        }

        private boolean hit(int bps) {
            return bps > 0 && random.nextInt(FailureMix.FULL_BPS) < bps;
        }

        private int between(int minInclusive, int maxInclusive) {
            if (maxInclusive <= minInclusive) {
                return minInclusive;
            }
            return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
        }

        private String cardLast4() {
            return String.format(Locale.ROOT, "%04d", between(0, 9999));
        }

        private String authCode() {
            return "AUTH" + String.format(Locale.ROOT, "%06d", between(0, 999999));
        }

        private String trackingNumber(String carrier) {
            return slug(carrier).toUpperCase(Locale.ROOT).substring(0, Math.min(3, carrier.length()))
                    + between(100000000, 999999999);
        }

        private void bump(String key) {
            counts.merge(key, 1L, Long::sum);
        }
    }

    /** Simulator-private Kafka headers, alongside the platform {@link EventHeaders}. */
    public static final String SIM_SEED_HEADER = "pdei-sim-seed";
    public static final String SIM_OCCURRED_AT_HEADER = "pdei-sim-occurred-at";

    // ===========================================================================================
    // Static helpers shared by the nested generator
    // ===========================================================================================

    /**
     * Insertion-ordered map from alternating key/value pairs.
     *
     * <p>{@link LinkedHashMap} rather than {@link Map#of} because {@code Map.of} has a
     * randomised iteration order (it is salted per JVM), which would make the serialised event
     * bodies differ between runs and destroy the determinism guarantee. Null values are kept:
     * {@code Json.mapper()} is configured NON_NULL, so they simply do not appear on the wire.
     */
    static Map<String, Object> orderedMap(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("orderedMap needs an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }

    /** Insertion-ordered String map for the document fields handed to {@link SyntheticArtifact}. */
    static Map<String, String> fields(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("fields needs an even number of arguments");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    /** Lower-case, hyphenated form of a display name, for synthetic email domains. */
    static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}
