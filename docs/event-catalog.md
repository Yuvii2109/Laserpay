# Event Catalog

Every canonical event in PDEI, its payload, its producer, and what consumes it.
Envelope fields are defined once in `PLATFORM-CONTRACT.md` §3 and are not repeated per event -
only the `payload` object is described below.

Conventions:
- All money fields are `{ "amountMinor": <long>, "currency": "<ISO-4217>" }`.
- All timestamps are ISO-8601 UTC strings.
- All IDs use the prefixes in `PLATFORM-CONTRACT.md` §5.
- `occurredAt` is when the fact happened in the source system; `observedAt` is when PDEI saw it.
  The gap between them is *lateness* and is deliberately preserved, never collapsed.

---

## 1. Payment events - `aggregateType: PAYMENT`

### `PaymentCreated`
Produced by: PSP adapter · Consumed by: state-builder-worker
```json
{ "paymentId": "PAY-…", "transactionId": "TX-…", "customerId": "CUS-…",
  "amount": {"amountMinor": 1299900, "currency": "INR"},
  "method": "CARD|UPI|NETBANKING|WALLET", "cardLast4": "4242",
  "cardNetwork": "VISA", "createdAt": "…" }
```

### `PaymentAuthorized`
```json
{ "paymentId": "PAY-…", "transactionId": "TX-…", "authorizationCode": "…",
  "authorizedAmount": {…}, "avsResult": "Y|N|U", "cvvResult": "M|N|P",
  "deviceFingerprint": "…", "authorizedAt": "…" }
```
State-builder derives `AVS_CVV_RESULT` and `DEVICE_FINGERPRINT` evidence from this event.

### `PaymentCaptured`
```json
{ "paymentId": "PAY-…", "transactionId": "TX-…", "capturedAmount": {…},
  "settlementReference": "…", "capturedAt": "…" }
```
**Derives evidence:** `PAYMENT_PROOF`. This is usually the first evidence a transaction gets.

### `PaymentFailed`
```json
{ "paymentId": "PAY-…", "transactionId": "TX-…", "failureCode": "…",
  "failureReason": "…", "failedAt": "…" }
```
Failed payments do not create dispute-eligible transactions.

---

## 2. Order events - `aggregateType: ORDER`

### `OrderCreated`
```json
{ "orderId": "ORD-…", "transactionId": "TX-…", "customerId": "CUS-…",
  "lines": [ {"sku": "…", "description": "…", "quantity": 2,
              "unitPrice": {…}, "lineTotal": {…}} ],
  "orderTotal": {…}, "shippingAddress": {…}, "billingAddress": {…},
  "placedAt": "…" }
```
**Derives evidence:** `ORDER_RECORD`, and an `INVOICE` artifact when the merchant emits one.

### `OrderFulfilled`
```json
{ "orderId": "ORD-…", "fulfilledLines": [{"sku": "…", "quantity": 2}],
  "fulfilledAt": "…" }
```

### `OrderCancelled`
```json
{ "orderId": "ORD-…", "reason": "…", "cancelledBy": "MERCHANT|CUSTOMER|SYSTEM",
  "cancelledAt": "…" }
```

---

## 3. Shipment events - `aggregateType: SHIPMENT`

### `ShipmentCreated`
```json
{ "shipmentId": "SHP-…", "orderId": "ORD-…", "carrier": "…",
  "trackingNumber": "…", "lines": [{"sku": "…", "quantity": 1}],
  "createdAt": "…" }
```
A single order may produce several shipments - the `multi-shipment-order` scenario exists
specifically to test that partial fulfilment does not read as a gap.

### `ShipmentDispatched`
```json
{ "shipmentId": "SHP-…", "dispatchedAt": "…", "originHub": "…",
  "estimatedDeliveryAt": "…" }
```
**Derives evidence:** `SHIPPING_RECORD`.

### `ShipmentDelivered`
```json
{ "shipmentId": "SHP-…", "deliveryId": "DLV-…", "deliveredAt": "…",
  "signedBy": "…", "deliveryAddress": {…}, "proofType": "SIGNATURE|PHOTO|OTP|GPS",
  "proofObjectKey": "…", "geo": {"lat": 0.0, "lon": 0.0} }
```
**Derives evidence:** `DELIVERY_PROOF` - the single most decisive artifact for
`GOODS_NOT_RECEIVED`, which is why its absence is a `CRITICAL` gap.

**Contradiction sources:** `deliveredAt` earlier than `ShipmentDispatched.dispatchedAt`;
`deliveryAddress` not matching `OrderCreated.shippingAddress`.

---

## 4. Refund events - `aggregateType: REFUND`

### `RefundCreated`
```json
{ "refundId": "REF-…", "paymentId": "PAY-…", "transactionId": "TX-…",
  "amount": {…}, "reason": "…", "requestedBy": "MERCHANT|CUSTOMER",
  "createdAt": "…" }
```

### `RefundProcessed`
```json
{ "refundId": "REF-…", "amount": {…}, "processedAt": "…",
  "settlementReference": "…", "isPartial": true }
```
**Derives evidence:** `REFUND_RECEIPT`. Decisive for `CREDIT_NOT_PROCESSED`.

**Contradiction source:** cumulative refunded amount exceeding `PaymentCaptured.capturedAmount`.

---

## 5. Communication events - `aggregateType: COMMUNICATION`

### `CommunicationCreated` (merchant → customer)
### `CommunicationReceived` (customer → merchant)
```json
{ "communicationId": "COM-…", "transactionId": "TX-…", "customerId": "CUS-…",
  "channel": "EMAIL|SMS|CHAT|PHONE|IN_APP",
  "direction": "OUTBOUND|INBOUND",
  "subject": "…", "bodyPreview": "…", "objectKey": "…/customer-email.eml",
  "occurredAt": "…" }
```
**Derives evidence:** `CUSTOMER_COMMUNICATION`. Usually `RECOMMENDED` rather than
`MANDATORY`, but it is the artifact that most often flips an ambiguous case, because it
establishes what the customer was told and when.

---

## 6. Evidence events - `aggregateType: EVIDENCE`

These are produced by the platform itself, not by external systems.

### `EvidenceAdded`
Produced by: state-builder-worker, document-processor-service, merchant portal upload
```json
{ "evidenceId": "EV-…", "transactionId": "TX-…", "type": "DELIVERY_PROOF",
  "status": "ACTIVE", "source": "LOGISTICS", "objectKey": "…",
  "sha256": "…", "version": 1, "parentVersion": null,
  "sourceEventId": "…", "expiresAt": "…", "createdAt": "…" }
```

### `EvidenceExpired`
Produced by: readiness-worker `ExpirySweepJob`
```json
{ "evidenceId": "EV-…", "transactionId": "TX-…", "type": "…",
  "previousStatus": "ACTIVE|EXPIRING", "expiredAt": "…", "reason": "RETENTION_WINDOW" }
```

### `EvidenceInvalidated`
Produced by: `EvidenceIntegrityService` (hash mismatch) or chaos injection
```json
{ "evidenceId": "EV-…", "transactionId": "TX-…",
  "reason": "HASH_MISMATCH|SOURCE_RETRACTED|SUPERSEDED|MANUAL",
  "expectedSha256": "…", "actualSha256": "…", "invalidatedAt": "…" }
```
Always triggers readiness recomputation and an audit entry.

---

## 7. Dispute events - `aggregateType: DISPUTE`

### `DisputeCreated`
Produced by: PSP adapter, or `POST /api/v1/disputes`, or chaos `INJECT_DISPUTE`
```json
{ "disputeId": "DSP-…", "transactionId": "TX-…", "paymentId": "PAY-…",
  "merchantId": "MER-…", "reasonCode": "GOODS_NOT_RECEIVED",
  "networkReasonCode": "13.1", "disputedAmount": {…},
  "status": "OPEN", "deadlineAt": "…", "receivedAt": "…" }
```
This is the event that starts a `DisputeCaseWorkflow` (workflow id `case-{caseId}`).
Duplicate deliveries are harmless - the workflow ID reuse policy absorbs them.

### `DisputeUpdated`
```json
{ "disputeId": "DSP-…", "previousStatus": "…", "status": "…",
  "deadlineAt": "…", "note": "…", "updatedAt": "…" }
```
Delivered to the running workflow as the `disputeUpdated` signal.

### `DisputeClosed`
```json
{ "disputeId": "DSP-…", "outcome": "WON|LOST|WITHDRAWN|EXPIRED",
  "recoveredAmount": {…}, "closedAt": "…" }
```
Ends the workflow's follow-up loop.

---

## 8. Readiness events - `aggregateType: TRANSACTION` (internal)

### `ReadinessRecomputed`
Produced by: readiness-worker
```json
{ "transactionId": "TX-…", "merchantId": "MER-…",
  "score": 92, "band": "READY", "previousScore": 78, "previousBand": "NEARLY_READY",
  "reasonCodeProfile": "BASELINE|GOODS_NOT_RECEIVED|…",
  "satisfiedRequirements": 5, "totalRequirements": 6,
  "penaltiesApplied": [{"type": "EXPIRING_SOON", "points": 5}],
  "policyVersion": 3, "computedAt": "…" }
```
Drives the `READINESS_UPDATED` WebSocket frame.

### `ReadinessGapDetected`
```json
{ "transactionId": "TX-…", "merchantId": "MER-…",
  "gaps": [{"type": "MISSING", "evidenceType": "DELIVERY_PROOF",
            "severity": "CRITICAL", "detail": "…", "detectedAt": "…"}] }
```
Drives the `GAP_DETECTED` frame and the at-risk feed behind `GET /gaps`.

---

## 9. Case events - `aggregateType: CASE` (internal)

Produced by: case-orchestrator-service, one per workflow step of consequence.

| Event | Emitted when | Notable payload |
|---|---|---|
| `CaseOpened` | workflow starts | `caseId`, `disputeId`, `readinessAtOpen`, `deadlineAt` |
| `CaseEvidenceAttached` | gatherEvidence completes | `attachedEvidenceIds[]`, `requirementCoverage` |
| `CaseInvestigated` | investigate returns | `investigationId`, `classification`, `confidence`, `aiInvoked` (bool), `bypassReason` |
| `CasePrepared` | package assembled | `packageObjectKey`, `manifestSha256`, `fileCount` |
| `CaseEscalated` | safety gate denied or human timeout | `escalationReason`, `failedRules[]` |
| `CaseSubmitted` | submitRepresentment succeeds | `submissionReference`, `submittedAt` |
| `CaseClosed` | workflow completes | `outcome`, `durationSeconds` |

`aiInvoked: false` with a populated `bypassReason` is how the funnel proves that
deterministic short-circuits are working - it is the source of the AI-reduction metric.

---

## 10. Audit events - topic `pdei.audit.events.v1`

Not a `CanonicalEvent`; uses the `AuditEvent` record (contract §3, SHARED-LIBRARY-API §1.3).

```json
{ "auditId": "AUD-…", "entityType": "EVIDENCE", "entityId": "EV-…",
  "merchantId": "MER-…", "action": "EVIDENCE_INVALIDATED",
  "actor": "readiness-worker", "actorType": "SYSTEM",
  "occurredAt": "…", "correlationId": "…",
  "before": {…}, "after": {…},
  "previousHash": "…", "hash": "…" }
```

`hash = sha256(canonicalJson(all fields except hash))`, chained per merchant.
`GET /audit/verify-chain?merchantId=` recomputes and reports the first divergence.

**Every** AI interaction is audited: the context sent, the result received, the validation
verdict, and the gate decision. This is what makes "AI proposes, policy disposes"
auditable rather than merely asserted.

---

## 11. Dead letters - topic `pdei.dlq.v1`

```json
{ "originalTopic": "pdei.raw.events.v1", "partition": 3, "offset": 918273,
  "consumerGroup": "pdei-normalization-worker",
  "failureClass": "UnknownEventTypeException",
  "failureMessage": "…", "stackTraceDigest": "…",
  "failedAt": "…", "attempt": 4, "originalPayload": {…} }
```

Nothing is ever silently dropped. An event that cannot be normalized lands here with
enough context to be replayed after the adapter is fixed.

---

## 12. Ordering and partitioning

All topics are keyed `merchantId + ":" + aggregateId`. This guarantees:

- all events for one payment land on one partition, in order;
- all events for one shipment land on one partition, in order;
- events for *different* aggregates of the same transaction may interleave.

That last point is deliberate and is why consumers cannot assume cross-aggregate ordering.
A `ShipmentDelivered` may be processed before the `OrderCreated` it belongs to. Handlers
tolerate this by writing what they know and letting readiness recomputation converge once
both facts land - the system is eventually consistent by construction, not by accident.
