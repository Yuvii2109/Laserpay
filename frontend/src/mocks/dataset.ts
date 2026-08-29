/**
 * Deterministic fixture dataset.
 *
 * Why it exists: the console must render something meaningful before any backend runs, and a
 * demo must be reproducible. `buildMockDataset(seed, now)` is a pure function - the same seed
 * produces the same merchants, transactions, evidence, gaps, disputes, cases and audit chain.
 * Only the time anchor moves (timestamps are offsets from `now`) so relative renderings such as
 * "6m ago" stay alive.
 *
 * Internal consistency matters more than volume here:
 *   - readiness scores are computed with the contract 7 formula over the generated evidence,
 *     so the requirement table on screen explains the number beside it;
 *   - requirement matrices mirror `DefaultPolicyMatrix` (see matrix.ts);
 *   - money is minor units + currency throughout, and the four merchants deliberately span
 *     exponent 0 (JPY), 2 (INR, GBP) and 3 (KWD) so a formatting regression is visible.
 */
import type { Money } from '@/lib/types/common';
import type { AggregateType, TimelineEntry } from '@/lib/types/events';
import type {
  EvidenceGraph,
  EvidenceLineage,
  EvidenceStatus,
  EvidenceType,
  EvidenceView,
  EvidenceVersionRecord,
  IntegrityReport,
} from '@/lib/types/evidence';
import type {
  ContradictionView,
  GapFeedItem,
  ReadinessGap,
  ReadinessSnapshot,
  RequirementView,
} from '@/lib/types/readiness';
import type { DisputeReasonCode, DisputeStatus, DisputeView } from '@/lib/types/dispute';
import type { CaseStatus, CaseView, CaseXRay, PackageManifest } from '@/lib/types/case';
import type { InvestigationRecord } from '@/lib/types/ai';
import type { PolicyView } from '@/lib/types/policy';
import type { AuditEventView } from '@/lib/types/audit';
import type { MerchantSummary, MerchantView } from '@/lib/types/merchant';
import type {
  TransactionDetail,
  TransactionFacts,
  TransactionView,
} from '@/lib/types/transaction';
import type { FunnelMetrics } from '@/lib/types/metrics';
import type { ChaosInjection, Scenario, SimulationRun } from '@/lib/types/simulation';
import { bandFromScore } from '@/lib/format/score';
import { currencyExponent } from '@/lib/format/money';
import {
  DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE,
  DEFAULT_EXPIRING_SOON_DAYS,
  DEFAULT_HUMAN_REVIEW_ABOVE_AMOUNT_MINOR,
  DEFAULT_MAX_CONTRADICTIONS,
  DEFAULT_MIN_READINESS_FOR_AUTO_PREPARE,
  DEFAULT_RESPONSE_WINDOW_DAYS,
  MAX_AGE_DAYS,
  requirementsFor,
} from './matrix';
import { createRng, seededUuid, sequentialId } from './random';

export const DEFAULT_MOCK_SEED = 20260826;

const DAY = 86_400_000;
const HOUR = 3_600_000;

export interface MockDataset {
  generatedAt: string;
  seed: number;
  merchants: MerchantView[];
  summaries: Record<string, MerchantSummary>;
  /** `GET /transactions` rows. */
  transactions: TransactionView[];
  /** `GET /transactions/{id}` - the nested TransactionDetailResponse shape. */
  transactionsById: Record<string, TransactionDetail>;
  evidenceById: Record<string, EvidenceView>;
  evidenceByTransaction: Record<string, EvidenceView[]>;
  versionsByEvidence: Record<string, EvidenceVersionRecord[]>;
  lineageByEvidence: Record<string, EvidenceLineage>;
  integrityByEvidence: Record<string, IntegrityReport>;
  readinessByTransaction: Record<string, ReadinessSnapshot>;
  timelineByTransaction: Record<string, TimelineEntry[]>;
  graphByTransaction: Record<string, EvidenceGraph>;
  gaps: GapFeedItem[];
  /**
   * `GET /gaps` is merchant-scoped and a gap row carries no merchantId, so the mock keeps the
   * ownership it needs for filtering beside the rows rather than inside them.
   */
  gapsByMerchant: Record<string, GapFeedItem[]>;
  disputes: DisputeView[];
  disputesById: Record<string, DisputeView>;
  cases: CaseView[];
  casesById: Record<string, CaseView>;
  xrayByCase: Record<string, CaseXRay>;
  packageByCase: Record<string, PackageManifest>;
  investigationsById: Record<string, InvestigationRecord>;
  policies: PolicyView[];
  policiesById: Record<string, PolicyView>;
  auditEvents: AuditEventView[];
  funnelByMerchant: Record<string, FunnelMetrics>;
  simulationRuns: SimulationRun[];
  chaosInjections: ChaosInjection[];
  scenarios: Scenario[];
}

interface MerchantSeed {
  merchantId: string;
  legalName: string;
  displayName: string;
  country: string;
  currency: string;
  mcc: string;
  winRateBps: number;
  topReason: DisputeReasonCode;
  transactions: number;
  /** Minimum/maximum transaction value in minor units for this currency. */
  amountRange: [number, number];
}

const MERCHANT_SEEDS: readonly MerchantSeed[] = [
  {
    merchantId: 'MER-0001',
    legalName: 'Northwind Retail Private Limited',
    displayName: 'Northwind Retail',
    country: 'IN',
    currency: 'INR',
    mcc: '5691',
    winRateBps: 7100,
    topReason: 'GOODS_NOT_RECEIVED',
    transactions: 22,
    amountRange: [89_900, 4_499_900],
  },
  {
    merchantId: 'MER-0002',
    legalName: 'Harbour Goods Limited',
    displayName: 'Harbour Goods',
    country: 'GB',
    currency: 'GBP',
    mcc: '5722',
    winRateBps: 6400,
    topReason: 'PRODUCT_NOT_AS_DESCRIBED',
    transactions: 18,
    amountRange: [1_999, 84_500],
  },
  {
    merchantId: 'MER-0003',
    legalName: 'Kyoto Kitchen K.K.',
    displayName: 'Kyoto Kitchen',
    country: 'JP',
    currency: 'JPY',
    mcc: '5812',
    winRateBps: 5800,
    topReason: 'SERVICE_NOT_RENDERED',
    transactions: 14,
    // JPY has exponent 0: these ARE yen, not sen.
    amountRange: [1_200, 168_000],
  },
  {
    merchantId: 'MER-0004',
    legalName: 'Gulf Traders W.L.L.',
    displayName: 'Gulf Traders',
    country: 'KW',
    currency: 'KWD',
    mcc: '5045',
    winRateBps: 6900,
    topReason: 'FRAUDULENT_TRANSACTION',
    transactions: 12,
    // KWD has exponent 3: 12_500 minor units is KWD 12.500.
    amountRange: [12_500, 1_450_000],
  },
];

const CARRIERS = ['BlueDart', 'DHL', 'Royal Mail', 'Yamato', 'Aramex'] as const;
const CHANNELS = ['WEB', 'MOBILE_APP', 'MARKETPLACE', 'PHONE'] as const;
const OPERATORS = ['ops.reyes', 'ops.tanaka', 'ops.mistry', 'risk.almeida'] as const;

const EVIDENCE_SUMMARY: Readonly<Record<EvidenceType, string>> = {
  PAYMENT_PROOF: 'Processor capture receipt with authorisation code',
  INVOICE: 'Tax invoice issued to the cardholder',
  ORDER_RECORD: 'Order snapshot including line items and totals',
  SHIPPING_RECORD: 'Carrier manifest with tracking number',
  DELIVERY_PROOF: 'Signed proof of delivery with GPS stamp',
  REFUND_RECEIPT: 'Refund confirmation from the processor',
  CUSTOMER_COMMUNICATION: 'Email thread with the cardholder',
  MERCHANT_POLICY: 'Published returns and shipping policy',
  TERMS_OF_SERVICE: 'Terms accepted at checkout',
  AVS_CVV_RESULT: 'Address and CVV verification response',
  DEVICE_FINGERPRINT: 'Device and session fingerprint at checkout',
  PRIOR_TRANSACTION_HISTORY: 'Prior undisputed orders by the same cardholder',
  SIGNED_CONTRACT: 'Counter-signed service agreement',
};

const EVIDENCE_EXTENSION: Readonly<Partial<Record<EvidenceType, string>>> = {
  CUSTOMER_COMMUNICATION: 'eml',
  DEVICE_FINGERPRINT: 'json',
  AVS_CVV_RESULT: 'json',
  PRIOR_TRANSACTION_HISTORY: 'csv',
};

function iso(millis: number): string {
  return new Date(millis).toISOString();
}

function money(amountMinor: number, currency: string): Money {
  return { amountMinor: Math.round(amountMinor), currency };
}

/**
 * Rounds a raw minor-unit amount to a plausible shelf price for the currency exponent:
 * whole hundreds of yen, half-dinar steps, and `.99` endings for two-digit currencies.
 */
function tidyAmount(raw: number, currency: string): number {
  const exponent = currencyExponent(currency);
  const HUNDRED = 100;
  const HALF_UNIT = 500;
  if (exponent === 0) return Math.max(HUNDRED, Math.round(raw / HUNDRED) * HUNDRED);
  if (exponent === 3) return Math.max(HALF_UNIT, Math.round(raw / HALF_UNIT) * HALF_UNIT);
  return Math.max(99, Math.round(raw / HUNDRED) * HUNDRED - 1);
}

function filenameFor(type: EvidenceType, evidenceId: string): string {
  const extension = EVIDENCE_EXTENSION[type] ?? 'pdf';
  return `${type.toLowerCase()}-${evidenceId.toLowerCase()}.${extension}`;
}

function contentTypeFor(filename: string): string {
  if (filename.endsWith('.json')) return 'application/json';
  if (filename.endsWith('.csv')) return 'text/csv';
  if (filename.endsWith('.eml')) return 'message/rfc822';
  return 'application/pdf';
}

/** Contract 11 object layout. */
function objectKeyFor(
  merchantId: string,
  transactionId: string,
  type: EvidenceType,
  evidenceId: string,
  version: number,
  filename: string,
): string {
  return `${merchantId}/${transactionId}/${type}/${evidenceId}/v${version}/${filename}`;
}

/**
 * Contract 7 readiness formula, applied to the generated evidence so the score on screen is
 * explained by the requirement table beside it.
 */
function computeReadiness(
  requirements: RequirementView[],
  gaps: ReadinessGap[],
  contradictions: ContradictionView[],
): { score: number; baseScore: number; penaltyPoints: number } {
  let satisfiedWeight = 0;
  let totalWeight = 0;
  for (const requirement of requirements) {
    const factor = requirement.strength === 'RECOMMENDED' ? 0.5 : 1;
    if (requirement.strength === 'OPTIONAL' || requirement.strength === 'PROHIBITED') continue;
    totalWeight += requirement.weight * factor;
    if (requirement.satisfied) satisfiedWeight += requirement.weight * factor;
  }
  const baseScore = totalWeight === 0 ? 0 : (100 * satisfiedWeight) / totalWeight;

  let penalty = 0;
  penalty += contradictions.length * 15;
  for (const gap of gaps) {
    const mandatory = requirements.some(
      (requirement) => requirement.type === gap.evidenceType && requirement.strength === 'MANDATORY',
    );
    if (!mandatory) continue;
    if (gap.type === 'EXPIRED') penalty += 10;
    if (gap.type === 'EXPIRING_SOON') penalty += 5;
    if (gap.type === 'UNVERIFIABLE_PROVENANCE') penalty += 20;
  }

  const score = Math.max(0, Math.min(100, Math.round(baseScore - penalty)));
  return { score, baseScore: Number(baseScore.toFixed(2)), penaltyPoints: penalty };
}

interface BuiltTransaction {
  detail: TransactionView;
  facts: TransactionFacts;
  evidence: EvidenceView[];
  readiness: ReadinessSnapshot;
  timeline: TimelineEntry[];
  graph: EvidenceGraph;
  gaps: ReadinessGap[];
  contradictions: ContradictionView[];
  reasonCode: DisputeReasonCode;
}

export function buildMockDataset(seed = DEFAULT_MOCK_SEED, now: Date = new Date()): MockDataset {
  const rng = createRng(seed);
  const nowMs = now.getTime();

  const merchants: MerchantView[] = [];
  const transactions: TransactionView[] = [];
  const evidenceById: Record<string, EvidenceView> = {};
  const evidenceByTransaction: Record<string, EvidenceView[]> = {};
  const versionsByEvidence: Record<string, EvidenceVersionRecord[]> = {};
  const lineageByEvidence: Record<string, EvidenceLineage> = {};
  const integrityByEvidence: Record<string, IntegrityReport> = {};
  const readinessByTransaction: Record<string, ReadinessSnapshot> = {};
  const timelineByTransaction: Record<string, TimelineEntry[]> = {};
  const graphByTransaction: Record<string, EvidenceGraph> = {};
  const gaps: GapFeedItem[] = [];
  const gapsByMerchant: Record<string, GapFeedItem[]> = {};
  const disputes: DisputeView[] = [];
  const cases: CaseView[] = [];
  const xrayByCase: Record<string, CaseXRay> = {};
  const packageByCase: Record<string, PackageManifest> = {};
  const investigationsById: Record<string, InvestigationRecord> = {};
  const policies: PolicyView[] = [];
  const auditEvents: AuditEventView[] = [];
  const funnelByMerchant: Record<string, FunnelMetrics> = {};
  const summaries: Record<string, MerchantSummary> = {};

  let transactionCounter = 0;
  let evidenceCounter = 0;
  let disputeCounter = 0;
  let caseCounter = 0;
  let investigationCounter = 0;
  let policyCounter = 0;

  const builtByMerchant: Record<string, BuiltTransaction[]> = {};

  for (const seedRow of MERCHANT_SEEDS) {
    merchants.push({
      merchantId: seedRow.merchantId,
      legalName: seedRow.legalName,
      displayName: seedRow.displayName,
      country: seedRow.country,
      defaultCurrency: seedRow.currency,
      mcc: seedRow.mcc,
      status: 'ACTIVE',
      timezone: 'UTC',
      contactEmail: `disputes@${seedRow.displayName.toLowerCase().replace(/\s+/g, '')}.example`,
      baselineWinRateBps: seedRow.winRateBps,
      onboardedAt: iso(nowMs - rng.int(200, 900) * DAY),
      riskProfile: { tier: rng.pick(['LOW', 'MEDIUM', 'ELEVATED']), chargebackRateBps: rng.int(12, 96) },
      metadata: { segment: rng.pick(['SMB', 'MID_MARKET', 'ENTERPRISE']) },
    });

    const built: BuiltTransaction[] = [];

    for (let index = 0; index < seedRow.transactions; index += 1) {
      transactionCounter += 1;
      const transactionId = sequentialId('TX-', transactionCounter);
      const customerId = sequentialId('CUS-', rng.int(1, 40), 5);
      const occurredMs = nowMs - rng.int(1, 55) * DAY - rng.int(0, 23) * HOUR;
      const observedMs = occurredMs + rng.int(200, 5_000);
      const [minAmount, maxAmount] = seedRow.amountRange;
      const amountMinor = tidyAmount(rng.int(minAmount, maxAmount), seedRow.currency);
      const reasonCode: DisputeReasonCode = rng.chance(0.6)
        ? seedRow.topReason
        : rng.pick([
            'GOODS_NOT_RECEIVED',
            'PRODUCT_NOT_AS_DESCRIBED',
            'FRAUDULENT_TRANSACTION',
            'SERVICE_NOT_RENDERED',
            'CREDIT_NOT_PROCESSED',
            'SUBSCRIPTION_CANCELLED',
            'INCORRECT_AMOUNT',
          ] as const);

      const refunded = rng.chance(0.12);
      const status = refunded ? 'REFUNDED' : rng.chance(0.92) ? 'CAPTURED' : 'AUTHORIZED';
      const specs = requirementsFor(reasonCode);

      /* ---- evidence ---- */
      const evidence: EvidenceView[] = [];
      const requirementViews: RequirementView[] = [];
      const transactionGaps: ReadinessGap[] = [];

      for (const spec of specs) {
        // Tuned so the fixture spans every band with a realistic majority in good shape:
        // roughly 60% of transactions have every mandatory artifact, the rest show real gaps.
        const presenceProbability =
          spec.strength === 'MANDATORY' ? 0.9 : spec.strength === 'RECOMMENDED' ? 0.72 : 0.4;
        const present = rng.chance(presenceProbability);

        if (!present) {
          requirementViews.push({
            type: spec.type,
            strength: spec.strength,
            satisfied: false,
            satisfyingEvidenceIds: [],
            weight: spec.weight,
            note: null,
          });
          if (spec.strength !== 'OPTIONAL') {
            transactionGaps.push({
              gapId: sequentialId('GAP-', transactionGaps.length + 1 + transactionCounter * 10, 6),
              transactionId,
              type: 'MISSING',
              evidenceType: spec.type,
              severity: spec.strength === 'MANDATORY' ? 'HIGH' : 'MEDIUM',
              evidenceId: null,
              detail: `No ${spec.type.replace(/_/g, ' ').toLowerCase()} captured for this transaction`,
              detectedAt: iso(occurredMs + rng.int(1, 6) * HOUR),
              expiresAt: null,
            });
          }
          continue;
        }

        evidenceCounter += 1;
        const evidenceId = sequentialId('EV-', evidenceCounter);
        const version = rng.chance(0.22) ? 2 : 1;
        const createdMs = occurredMs + rng.int(1, 48) * HOUR;
        const maxAge = MAX_AGE_DAYS[spec.type] ?? null;
        const expiresMs = maxAge ? createdMs + maxAge * DAY : null;

        // Most artifacts are healthy; a minority exercise every EvidenceStatus.
        let evidenceStatus: EvidenceStatus = 'ACTIVE';
        let effectiveExpiry = expiresMs;
        const roll = rng.next();
        if (roll < 0.05) {
          evidenceStatus = 'EXPIRED';
          effectiveExpiry = nowMs - rng.int(1, 30) * DAY;
        } else if (roll < 0.11) {
          evidenceStatus = 'EXPIRING';
          effectiveExpiry = nowMs + rng.int(1, DEFAULT_EXPIRING_SOON_DAYS) * DAY;
        } else if (roll < 0.13) {
          evidenceStatus = 'INVALIDATED';
        } else if (roll < 0.15) {
          evidenceStatus = 'PENDING';
        } else if (roll < 0.17 && version > 1) {
          // A superseded artifact only makes sense when a newer version replaced it.
          evidenceStatus = 'SUPERSEDED';
        }

        const provenanceVerified = rng.chance(0.95);
        const filename = filenameFor(spec.type, evidenceId);
        const usable = evidenceStatus === 'ACTIVE' || evidenceStatus === 'EXPIRING';
        const satisfied = usable && (!spec.provenanceRequired || provenanceVerified);

        const view: EvidenceView = {
          evidenceId,
          merchantId: seedRow.merchantId,
          transactionId,
          type: spec.type,
          status: evidenceStatus,
          source: rng.pick([
            'PSP_ADAPTER',
            'ORDER_SYSTEM',
            'LOGISTICS',
            'CRM',
            'DOCUMENT_UPLOAD',
            'MERCHANT_PORTAL',
          ] as const),
          objectKey: objectKeyFor(seedRow.merchantId, transactionId, spec.type, evidenceId, version, filename),
          sha256: rng.hex(64),
          version,
          filename,
          contentType: contentTypeFor(filename),
          sizeBytes: rng.int(18_000, 3_200_000),
          summary: EVIDENCE_SUMMARY[spec.type],
          sourceEventId: provenanceVerified ? seededUuid(rng) : null,
          parentEvidenceId: null,
          relatedEntityId: null,
          qualityScore: Number((0.62 + rng.next() * 0.38).toFixed(2)),
          provenanceVerified,
          createdAt: iso(createdMs),
          observedAt: iso(createdMs + rng.int(100, 4_000)),
          expiresAt: effectiveExpiry ? iso(effectiveExpiry) : null,
        };

        evidence.push(view);
        evidenceById[evidenceId] = view;

        requirementViews.push({
          type: spec.type,
          strength: spec.strength,
          satisfied,
          satisfyingEvidenceIds: satisfied ? [evidenceId] : [],
          weight: spec.weight,
          note: satisfied ? null : `Present but ${evidenceStatus.toLowerCase()}`,
        });

        if (evidenceStatus === 'EXPIRED') {
          transactionGaps.push({
            gapId: `GAP-${evidenceId.slice(3)}-EXP`,
            transactionId,
            type: 'EXPIRED',
            evidenceType: spec.type,
            severity: spec.strength === 'MANDATORY' ? 'HIGH' : 'MEDIUM',
            evidenceId,
            detail: 'Artifact is past its retention window and no longer satisfies the requirement',
            detectedAt: iso(nowMs - rng.int(1, 5) * DAY),
            expiresAt: view.expiresAt,
          });
        } else if (evidenceStatus === 'EXPIRING') {
          transactionGaps.push({
            gapId: `GAP-${evidenceId.slice(3)}-SOON`,
            transactionId,
            type: 'EXPIRING_SOON',
            evidenceType: spec.type,
            severity: 'MEDIUM',
            evidenceId,
            detail: `Expires within ${DEFAULT_EXPIRING_SOON_DAYS} days; re-capture before a dispute lands`,
            detectedAt: iso(nowMs - rng.int(1, 3) * DAY),
            expiresAt: view.expiresAt,
          });
        } else if (!provenanceVerified && spec.strength === 'MANDATORY') {
          transactionGaps.push({
            gapId: `GAP-${evidenceId.slice(3)}-PROV`,
            transactionId,
            type: 'UNVERIFIABLE_PROVENANCE',
            evidenceType: spec.type,
            severity: 'HIGH',
            evidenceId,
            detail: 'No source event recorded; provenance cannot be proven',
            detectedAt: iso(createdMs + HOUR),
            expiresAt: null,
          });
        }

        /* versions + lineage */
        const versionRows: EvidenceVersionRecord[] = [];
        for (let v = 1; v <= version; v += 1) {
          versionRows.push({
            evidenceVersionId: `EVV-${evidenceId.slice(3)}-${v}`,
            evidenceId,
            version: v,
            objectKey: objectKeyFor(seedRow.merchantId, transactionId, spec.type, evidenceId, v, filename),
            sha256: v === version ? view.sha256 : rng.hex(64),
            sizeBytes: view.sizeBytes - (version - v) * 1_024,
            contentType: view.contentType,
            filename,
            sourceEventId: view.sourceEventId,
            createdBy: rng.pick(OPERATORS),
            createdAt: iso(createdMs - (version - v) * DAY),
          });
        }
        versionsByEvidence[evidenceId] = versionRows;
        lineageByEvidence[evidenceId] = {
          evidenceId,
          versions: versionRows,
          relationships: [
            {
              relationshipId: `REL-${evidenceId.slice(3)}-1`,
              fromEvidenceId: evidenceId,
              toEvidenceId: transactionId,
              relation: 'EVIDENCES',
              detail: 'Attached to the transaction under investigation',
              createdAt: view.createdAt,
            },
          ],
          rootEvidenceId: evidenceId,
          ancestry: version > 1 ? [`${evidenceId}#v1`] : [],
          generatedAt: iso(nowMs),
        };
        integrityByEvidence[evidenceId] = {
          evidenceId,
          objectKey: view.objectKey,
          intact: evidenceStatus !== 'INVALIDATED',
          objectMissing: false,
          expectedSha256: view.sha256,
          actualSha256: evidenceStatus === 'INVALIDATED' ? rng.hex(64) : view.sha256,
          detail:
            evidenceStatus === 'INVALIDATED'
              ? 'stored object hash does not match the recorded sha256'
              : null,
          verifiedAt: iso(nowMs - rng.int(1, 40) * HOUR),
        };
      }

      /* ---- contradictions ---- */
      const contradictions: ContradictionView[] = [];
      const delivery = evidence.find((item) => item.type === 'DELIVERY_PROOF');
      const shipping = evidence.find((item) => item.type === 'SHIPPING_RECORD');
      if (delivery && shipping && rng.chance(0.18)) {
        contradictions.push({
          left: shipping.evidenceId,
          right: delivery.evidenceId,
          field: 'deliveredAt',
          detail: 'Delivery confirmation predates the carrier dispatch scan',
          severity: 'HIGH',
          leftValue: iso(occurredMs + 60 * HOUR),
          rightValue: iso(occurredMs + 36 * HOUR),
          detectedAt: iso(nowMs - rng.int(2, 20) * HOUR),
        });
        transactionGaps.push({
          gapId: `GAP-${transactionId.slice(3)}-CON`,
          transactionId,
          type: 'CONTRADICTORY',
          evidenceType: 'DELIVERY_PROOF',
          severity: 'CRITICAL',
          evidenceId: delivery.evidenceId,
          detail: 'Delivery and shipping records disagree on the delivery timestamp',
          detectedAt: iso(nowMs - rng.int(2, 20) * HOUR),
          expiresAt: null,
        });
      }

      const { score, baseScore, penaltyPoints } = computeReadiness(
        requirementViews,
        transactionGaps,
        contradictions,
      );
      const band = bandFromScore(score) ?? 'NOT_READY';

      const readiness: ReadinessSnapshot = {
        snapshotId: `RS-${transactionId.slice(3)}`,
        transactionId,
        merchantId: seedRow.merchantId,
        reasonCode,
        score,
        band,
        baseScore,
        penaltyPoints,
        requirements: requirementViews,
        gaps: transactionGaps,
        contradictions,
        policyVersionId: `POLV-${seedRow.merchantId.slice(4)}-${reasonCode.slice(0, 3)}-1`,
        computedAt: iso(nowMs - rng.int(1, 600) * 60_000),
      };

      /* ---- facts ---- */
      const paymentId = sequentialId('PAY-', transactionCounter);
      const orderId = sequentialId('ORD-', transactionCounter);
      const shipmentId = sequentialId('SHP-', transactionCounter);
      const deliveryId = sequentialId('DLV-', transactionCounter);
      const carrier = rng.pick(CARRIERS);
      const dispatchedMs = occurredMs + rng.int(6, 40) * HOUR;
      const deliveredMs = dispatchedMs + rng.int(12, 96) * HOUR;
      const deliveredInPast = deliveredMs < nowMs;

      const facts: TransactionFacts = {
        transactionId,
        merchantId: seedRow.merchantId,
        customerId,
        amount: money(amountMinor, seedRow.currency),
        status,
        createdAt: iso(occurredMs),
        payments: [
          {
            paymentId,
            status: status === 'AUTHORIZED' ? 'AUTHORIZED' : 'CAPTURED',
            amount: money(amountMinor, seedRow.currency),
            processorReference: `psp_${rng.hex(12)}`,
            createdAt: iso(occurredMs),
            authorizedAt: iso(occurredMs + 2_000),
            capturedAt: status === 'AUTHORIZED' ? null : iso(occurredMs + rng.int(1, 8) * HOUR),
            avsResult: rng.pick(['Y', 'A', 'Z', 'N']),
            cvvResult: rng.pick(['M', 'N', 'P']),
          },
        ],
        orders: [
          {
            orderId,
            status: refunded ? 'REFUNDED' : 'FULFILLED',
            total: money(amountMinor, seedRow.currency),
            shippingAddress: `${rng.int(1, 200)} Example Street, ${seedRow.country}`,
            createdAt: iso(occurredMs),
            fulfilledAt: iso(dispatchedMs),
            lines: [
              {
                lineId: `${orderId}-L1`,
                sku: `SKU-${rng.hex(6).toUpperCase()}`,
                description: rng.pick([
                  'Wireless headphones',
                  'Espresso machine',
                  'Running shoes',
                  'Standing desk',
                  'Camera lens',
                ]),
                quantity: rng.int(1, 3),
                unitPrice: money(amountMinor, seedRow.currency),
              },
            ],
          },
        ],
        shipments: [
          {
            shipmentId,
            orderId,
            carrier,
            trackingNumber: rng.hex(10).toUpperCase(),
            status: deliveredInPast ? 'DELIVERED' : 'IN_TRANSIT',
            destinationAddress: `${rng.int(1, 200)} Example Street, ${seedRow.country}`,
            quantity: 1,
            createdAt: iso(occurredMs + HOUR),
            dispatchedAt: iso(dispatchedMs),
          },
        ],
        deliveries: deliveredInPast
          ? [
              {
                deliveryId,
                shipmentId,
                status: 'DELIVERED',
                signedBy: rng.pick(['A. Kumar', 'J. Smith', 'M. Sato', 'F. Nasser']),
                deliveredToAddress: `${rng.int(1, 200)} Example Street, ${seedRow.country}`,
                proofType: rng.pick(['SIGNATURE', 'PHOTO', 'GEO_STAMP']),
                deliveredAt: iso(deliveredMs),
              },
            ]
          : [],
        refunds: refunded
          ? [
              {
                refundId: sequentialId('REF-', transactionCounter),
                paymentId,
                status: 'PROCESSED',
                amount: money(Math.round(amountMinor / 2), seedRow.currency),
                createdAt: iso(deliveredMs + DAY),
                processedAt: iso(deliveredMs + DAY + 2 * HOUR),
              },
            ]
          : [],
        communications: rng.chance(0.6)
          ? [
              {
                communicationId: sequentialId('COM-', transactionCounter),
                channel: 'EMAIL',
                direction: 'INBOUND',
                subject: 'Where is my order?',
                body: 'The tracking page has not updated since dispatch.',
                occurredAt: iso(dispatchedMs + 12 * HOUR),
              },
            ]
          : [],
      };

      const detail: TransactionView = {
        transactionId,
        merchantId: seedRow.merchantId,
        customerId,
        externalRef: `${seedRow.merchantId.slice(0, 3)}-${rng.hex(6).toUpperCase()}`,
        amount: money(amountMinor, seedRow.currency),
        capturedAmount: status === 'AUTHORIZED' ? null : money(amountMinor, seedRow.currency),
        refundedAmount: refunded ? money(Math.round(amountMinor / 2), seedRow.currency) : null,
        status,
        channel: rng.pick(CHANNELS),
        occurredAt: iso(occurredMs),
        observedAt: iso(observedMs),
        readinessScore: score,
        readinessBand: band,
        readinessComputedAt: readiness.computedAt,
        lastEventId: seededUuid(rng),
        lastEventAt: iso(Math.min(nowMs, deliveredMs)),
        disputeId: null,
        evidenceCount: evidence.length,
        openGapCount: transactionGaps.length,
        metadata: { channel: 'seeded-fixture', reasonProfile: reasonCode },
      };

      /* ---- timeline ---- */
      const timeline: TimelineEntry[] = [
        {
          entryId: `${paymentId}@PaymentCaptured`,
          at: iso(occurredMs),
          eventType: 'PaymentCaptured',
          aggregateType: 'PAYMENT',
          aggregateId: paymentId,
          summary: 'Payment captured by the processor',
          source: 'PSP_ADAPTER',
          details: { processorReference: `psp_${rng.hex(8)}` },
        },
        {
          entryId: `${orderId}@OrderCreated`,
          at: iso(occurredMs + 30 * 60_000),
          eventType: 'OrderCreated',
          aggregateType: 'ORDER',
          aggregateId: orderId,
          summary: 'Order created in the commerce platform',
          source: 'ORDER_SYSTEM',
          details: {},
        },
        {
          entryId: `${shipmentId}@ShipmentDispatched`,
          at: iso(dispatchedMs),
          eventType: 'ShipmentDispatched',
          aggregateType: 'SHIPMENT',
          aggregateId: shipmentId,
          summary: `Dispatched via ${carrier}`,
          source: 'LOGISTICS',
          details: { carrier },
        },
      ];
      if (deliveredInPast) {
        timeline.push({
          entryId: `${deliveryId}@ShipmentDelivered`,
          at: iso(deliveredMs),
          eventType: 'ShipmentDelivered',
          aggregateType: 'DELIVERY',
          aggregateId: deliveryId,
          summary: 'Delivery confirmed with signature',
          source: 'LOGISTICS',
          details: {},
        });
      }
      for (const item of evidence) {
        timeline.push({
          entryId: `${item.evidenceId}@EvidenceAdded`,
          at: item.createdAt,
          eventType: 'EvidenceAdded',
          aggregateType: 'EVIDENCE',
          aggregateId: item.evidenceId,
          summary: `${item.type.replace(/_/g, ' ').toLowerCase()} captured (v${item.version})`,
          source: item.source,
          details: { sha256: item.sha256, status: item.status },
        });
      }
      timeline.sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime());

      /* ---- graph ---- */
      const graph: EvidenceGraph = {
        rootId: transactionId,
        nodes: [
          { id: transactionId, type: 'TRANSACTION' as AggregateType, label: transactionId, status, at: iso(occurredMs), attributes: {} },
          { id: paymentId, type: 'PAYMENT', label: 'Payment', status: 'CAPTURED', at: iso(occurredMs), attributes: {} },
          { id: orderId, type: 'ORDER', label: 'Order', status: 'FULFILLED', at: iso(occurredMs), attributes: {} },
          { id: shipmentId, type: 'SHIPMENT', label: carrier, status: deliveredInPast ? 'DELIVERED' : 'IN_TRANSIT', at: iso(dispatchedMs), attributes: {} },
          ...(deliveredInPast
            ? [
                {
                  id: deliveryId,
                  type: 'DELIVERY' as AggregateType,
                  label: 'Delivery',
                  status: 'DELIVERED',
                  at: iso(deliveredMs),
                  attributes: {},
                },
              ]
            : []),
          ...evidence.map((item) => ({
            id: item.evidenceId,
            type: 'EVIDENCE' as AggregateType,
            label: item.type,
            status: item.status,
            at: item.createdAt,
            attributes: { sha256: item.sha256, version: item.version },
          })),
        ],
        edges: [
          { from: transactionId, to: paymentId, relation: 'HAS_PAYMENT', attributes: {} },
          { from: transactionId, to: orderId, relation: 'HAS_ORDER', attributes: {} },
          { from: orderId, to: shipmentId, relation: 'SHIPPED_AS', attributes: {} },
          ...(deliveredInPast
            ? [{ from: shipmentId, to: deliveryId, relation: 'DELIVERED_AS', attributes: {} }]
            : []),
          ...evidence.map((item) => ({
            from: item.evidenceId,
            to: transactionId,
            relation: 'EVIDENCES',
            attributes: {},
          })),
          ...contradictions
            .filter((item) => item.left && item.right)
            .map((item) => ({
              from: item.left as string,
              to: item.right as string,
              relation: 'CONTRADICTS',
              attributes: { field: item.field ?? '' },
            })),
        ],
        generatedAt: iso(nowMs),
      };

      transactions.push(detail);
      evidenceByTransaction[transactionId] = evidence;
      readinessByTransaction[transactionId] = readiness;
      timelineByTransaction[transactionId] = timeline;
      graphByTransaction[transactionId] = graph;
      // A `GET /gaps` row is a bare core.model.ReadinessGap: no merchantId, no score, no band.
      const merchantGapRows = (gapsByMerchant[seedRow.merchantId] ??= []);
      for (const gap of transactionGaps) {
        gaps.push(gap);
        merchantGapRows.push(gap);
      }

      built.push({
        detail,
        facts,
        evidence,
        readiness,
        timeline,
        graph,
        gaps: transactionGaps,
        contradictions,
        reasonCode,
      });
    }

    builtByMerchant[seedRow.merchantId] = built;
  }

  /* ---- disputes, cases, investigations, packages ---- */
  /**
   * Cases are dealt round-robin across the workflow lanes rather than sampled at random, so the
   * case queue always has every swimlane populated - including FAILED, which a random draw
   * would often miss.
   */
  const CASE_LANES: readonly CaseStatus[] = [
    'CREATED',
    'ASSEMBLING',
    'AWAITING_EVIDENCE',
    'INVESTIGATING',
    'AWAITING_APPROVAL',
    'PREPARED',
    'SUBMITTED',
    'CLOSED',
    'FAILED',
  ];

  for (const seedRow of MERCHANT_SEEDS) {
    const built = builtByMerchant[seedRow.merchantId] ?? [];
    // Disputes land preferentially on weak transactions - that is the whole product thesis.
    const candidates = [...built].sort((a, b) => a.readiness.score - b.readiness.score);
    const disputeCount = Math.max(2, Math.round(built.length * 0.3));

    for (let index = 0; index < disputeCount && index < candidates.length; index += 1) {
      const candidate = candidates[index];
      if (!candidate) continue;
      disputeCounter += 1;
      caseCounter += 1;

      const disputeId = sequentialId('DSP-', disputeCounter, 5);
      const caseId = sequentialId('CASE-', caseCounter, 5);
      const openedMs = new Date(candidate.detail.occurredAt).getTime() + rng.int(3, 20) * DAY;
      const openedAtMs = Math.min(openedMs, nowMs - HOUR);
      const deadlineMs = openedAtMs + DEFAULT_RESPONSE_WINDOW_DAYS * DAY;
      const caseStatus: CaseStatus = CASE_LANES[(caseCounter - 1) % CASE_LANES.length] ?? 'CREATED';

      const disputeStatus: DisputeStatus =
        caseStatus === 'CLOSED'
          ? rng.pick(['WON', 'LOST'] as const)
          : caseStatus === 'SUBMITTED'
            ? 'SUBMITTED'
            : caseStatus === 'PREPARED'
              ? 'REPRESENTMENT_PREPARED'
              : caseStatus === 'AWAITING_APPROVAL'
                ? 'AWAITING_HUMAN_REVIEW'
                : caseStatus === 'INVESTIGATING'
                  ? 'UNDER_INVESTIGATION'
                  : caseStatus === 'AWAITING_EVIDENCE'
                    ? 'EVIDENCE_GATHERING'
                    : 'OPEN';

      const dispute: DisputeView = {
        disputeId,
        merchantId: seedRow.merchantId,
        transactionId: candidate.detail.transactionId,
        reasonCode: candidate.reasonCode,
        status: disputeStatus,
        amount: candidate.detail.amount,
        networkCaseRef: `NET-${rng.hex(8).toUpperCase()}`,
        source: rng.pick(['PSP_ADAPTER', 'SIMULATOR', 'MERCHANT_PORTAL']),
        openedAt: iso(openedAtMs),
        deadlineAt: iso(deadlineMs),
        closedAt: caseStatus === 'CLOSED' ? iso(openedAtMs + rng.int(3, 18) * DAY) : null,
        updatedAt: iso(Math.min(nowMs, openedAtMs + rng.int(1, 96) * HOUR)),
      };
      disputes.push(dispute);
      candidate.detail.disputeId = disputeId;

      const caseView: CaseView = {
        caseId,
        disputeId,
        merchantId: seedRow.merchantId,
        transactionId: candidate.detail.transactionId,
        status: caseStatus,
        workflowId: `case-${caseId}`,
        assignedTo: caseStatus === 'AWAITING_APPROVAL' ? rng.pick(OPERATORS) : null,
        packageVersion: ['PREPARED', 'SUBMITTED', 'CLOSED'].includes(caseStatus) ? 1 : 0,
        openedAt: iso(openedAtMs + HOUR),
        updatedAt: iso(Math.min(nowMs, openedAtMs + rng.int(2, 120) * HOUR)),
        closedAt: caseStatus === 'CLOSED' ? dispute.closedAt : null,
      };
      cases.push(caseView);

      /* investigation - only for cases that reached the reasoning stage */
      let investigation: InvestigationRecord | null = null;
      if (['INVESTIGATING', 'AWAITING_APPROVAL', 'PREPARED', 'SUBMITTED', 'CLOSED'].includes(caseStatus)) {
        investigationCounter += 1;
        const investigationId = sequentialId('INV-', investigationCounter, 5);
        const allMandatorySatisfied = candidate.readiness.requirements
          .filter((requirement) => requirement.strength === 'MANDATORY')
          .every((requirement) => requirement.satisfied);
        const deterministic = allMandatorySatisfied && candidate.contradictions.length === 0;
        const confidence = deterministic
          ? 1
          : Number((0.58 + rng.next() * 0.4).toFixed(3));
        const classification = deterministic
          ? 'DEFENDABLE'
          : candidate.readiness.score >= 75
            ? 'DEFENDABLE'
            : candidate.readiness.score >= 50
              ? 'AMBIGUOUS'
              : candidate.evidence.length === 0
                ? 'INSUFFICIENT_EVIDENCE'
                : 'WEAK';
        const recommendedAction =
          classification === 'DEFENDABLE'
            ? 'PREPARE_REPRESENTMENT'
            : classification === 'INSUFFICIENT_EVIDENCE'
              ? 'ACCEPT_LIABILITY'
              : classification === 'AMBIGUOUS'
                ? 'ESCALATE_TO_HUMAN'
                : 'GATHER_MORE_EVIDENCE';
        const denied =
          recommendedAction === 'PREPARE_REPRESENTMENT' &&
          (confidence < DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE ||
            candidate.contradictions.length > DEFAULT_MAX_CONTRADICTIONS);
        const supporting = candidate.evidence
          .filter((item) => item.status === 'ACTIVE')
          .slice(0, 3)
          .map((item) => item.evidenceId);

        investigation = {
          investigationId,
          caseId,
          disputeId,
          merchantId: seedRow.merchantId,
          transactionId: candidate.detail.transactionId,
          classification,
          confidence,
          recommendedAction,
          safetyDecision: denied ? 'DENY' : deterministic ? 'ALLOW' : 'ALLOW_WITH_REVIEW',
          provider: deterministic ? 'deterministic' : 'mock',
          model: deterministic ? 'pdei-deterministic-v1' : 'mock-reasoner-v1',
          latencyMs: deterministic ? rng.int(4, 40) : rng.int(600, 3_400),
          promptTokens: deterministic ? 0 : rng.int(1_800, 6_400),
          completionTokens: deterministic ? 0 : rng.int(220, 900),
          attempt: 1,
          reasoningSummary: deterministic
            ? 'All mandatory requirements satisfied with zero contradictions; resolved on the deterministic path without calling the model.'
            : `Readiness ${candidate.readiness.score}/100 with ${candidate.gaps.length} open gap(s); representment strength depends on the missing artifacts listed below.`,
          narrative: deterministic
            ? 'The cardholder was charged, the order shipped, and delivery was confirmed with a signed proof. Every mandatory artifact is present, current and hash-verified.'
            : 'Evidence supports part of the merchant position. The gaps below must be closed before this case can be defended with confidence.',
          result: {
            investigationId,
            classification,
            confidence,
            supportingEvidence: supporting,
            missingEvidence: candidate.readiness.requirements
              .filter((requirement) => !requirement.satisfied && requirement.strength === 'MANDATORY')
              .map((requirement) => requirement.type),
            contradictions: candidate.contradictions,
            reasoningSummary: 'See reasoningSummary above.',
            narrative: 'See narrative above.',
            recommendedAction,
            citations: supporting.map((evidenceId) => ({
              claim: 'Artifact supports the merchant position',
              evidenceId,
            })),
            modelMetadata: {
              provider: deterministic ? 'deterministic' : 'mock',
              model: deterministic ? 'pdei-deterministic-v1' : 'mock-reasoner-v1',
              promptTokens: deterministic ? 0 : rng.int(1_800, 6_400),
              completionTokens: deterministic ? 0 : rng.int(220, 900),
              latencyMs: deterministic ? rng.int(4, 40) : rng.int(600, 3_400),
              attempt: 1,
            },
          },
          verdict: {
            decision: denied ? 'DENY' : deterministic ? 'ALLOW' : 'ALLOW_WITH_REVIEW',
            reasons: denied
              ? [
                  `confidence ${confidence} is below the policy floor ${DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE}`,
                  ...(candidate.contradictions.length > DEFAULT_MAX_CONTRADICTIONS
                    ? ['contradictions exceed the policy maximum']
                    : []),
                ]
              : deterministic
                ? []
                : ['classification requires a human confirmation before submission'],
            unsupportedClaims: [],
          },
          admission: {
            admit: !deterministic,
            priority: deterministic ? 0 : rng.int(55, 96),
            reason: deterministic
              ? 'deterministic short-circuit: all mandatory requirements satisfied'
              : 'ambiguity above the admission threshold',
            shortCircuit: deterministic ? 'ALL_REQUIREMENTS_SATISFIED' : 'NONE',
            deterministicAction: deterministic ? 'PREPARE_REPRESENTMENT' : null,
            financialImpact: Number(rng.next().toFixed(2)),
            deadlineUrgency: Number(rng.next().toFixed(2)),
            ambiguityScore: Number(rng.next().toFixed(2)),
            // eslint-disable-next-line no-restricted-syntax -- readiness score to a 0-1 confidence, not money
            deterministicConfidence: Number((candidate.readiness.score / 100).toFixed(2)),
          },
          startedAt: iso(openedAtMs + 2 * HOUR),
          completedAt: iso(openedAtMs + 2 * HOUR + rng.int(1, 30) * 60_000),
        };
        investigationsById[investigationId] = investigation;
      }

      /* package manifest for cases that produced a bundle */
      let manifest: PackageManifest | null = null;
      if (['PREPARED', 'SUBMITTED', 'CLOSED'].includes(caseStatus)) {
        const items = candidate.evidence
          .filter((item) => item.status === 'ACTIVE' || item.status === 'EXPIRING')
          .map((item) => ({
            evidenceId: item.evidenceId,
            type: item.type,
            strength:
              candidate.readiness.requirements.find((requirement) => requirement.type === item.type)
                ?.strength ?? 'OPTIONAL',
            version: item.version,
            sha256: item.sha256,
            objectKey: item.objectKey,
            filename: item.filename,
            contentType: item.contentType,
            sizeBytes: item.sizeBytes,
            entryPath: `evidence/${item.type.toLowerCase()}/${item.filename}`,
            capturedAt: item.createdAt,
          }));
        manifest = {
          manifestId: `MAN-${caseId.slice(5)}`,
          caseId,
          disputeId,
          merchantId: seedRow.merchantId,
          transactionId: candidate.detail.transactionId,
          reasonCode: candidate.reasonCode,
          disputeAmount: candidate.detail.amount,
          packageVersion: 1,
          bundleObjectKey: `${seedRow.merchantId}/${caseId}/representment-${caseId}-v1.zip`,
          bundleSha256: rng.hex(64),
          bundleSizeBytes: items.reduce((total, item) => total + item.sizeBytes, 24_000),
          items,
          narrative: investigation?.narrative ?? 'Representment assembled from verified evidence.',
          policyVersionId: candidate.readiness.policyVersionId,
          readinessScore: candidate.readiness.score,
          readinessBand: candidate.readiness.band,
          generatedBy: 'case-orchestrator-service',
          generatedAt: iso(openedAtMs + 6 * HOUR),
        };
        packageByCase[caseId] = manifest;
      }

      xrayByCase[caseId] = {
        caseId,
        disputeId,
        transactionId: candidate.detail.transactionId,
        merchantId: seedRow.merchantId,
        caseStatus,
        disputeStatus,
        reasonCode: candidate.reasonCode,
        disputeAmount: candidate.detail.amount,
        deadlineAt: dispute.deadlineAt,
        readiness: candidate.readiness,
        evidence: candidate.evidence,
        graph: candidate.graph,
        timeline: candidate.timeline,
        gaps: candidate.gaps,
        contradictions: candidate.contradictions,
        investigation: investigation?.result ?? null,
        safetyVerdict: investigation?.verdict ?? null,
        packageManifest: manifest,
        auditEventIds: [],
        generatedAt: iso(nowMs),
      };

    }

    /* ---- policies: the merchant's top reason plus two more, and a baseline ---- */
    const reasonCodes: DisputeReasonCode[] = [
      seedRow.topReason,
      ...(['GOODS_NOT_RECEIVED', 'FRAUDULENT_TRANSACTION', 'CREDIT_NOT_PROCESSED'] as const).filter(
        (code) => code !== seedRow.topReason,
      ),
    ].slice(0, 3);

    for (const reasonCode of [...reasonCodes, null]) {
      policyCounter += 1;
      const policyId = sequentialId('POL-', policyCounter, 5);
      const version = rng.int(1, 4);
      policies.push({
        policyId,
        policyVersionId: `POLV-${policyId.slice(4)}-${version}`,
        version,
        merchantId: seedRow.merchantId,
        reasonCode,
        requirements: requirementsFor(reasonCode),
        permittedActions: [
          'PREPARE_REPRESENTMENT',
          'GATHER_MORE_EVIDENCE',
          'ESCALATE_TO_HUMAN',
          'ACCEPT_LIABILITY',
          'REQUEST_POLICY_REVIEW',
        ],
        prohibitedEvidenceTypes: reasonCode === 'FRAUDULENT_TRANSACTION' ? [] : ['DEVICE_FINGERPRINT'],
        autoPrepareMinConfidence: DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE,
        maxContradictions: DEFAULT_MAX_CONTRADICTIONS,
        minReadinessScoreForAutoPrepare: DEFAULT_MIN_READINESS_FOR_AUTO_PREPARE,
        humanReviewAboveAmountMinor: DEFAULT_HUMAN_REVIEW_ABOVE_AMOUNT_MINOR,
        currency: seedRow.currency,
        autoSubmitEnabled: false,
        responseWindowDays: DEFAULT_RESPONSE_WINDOW_DAYS,
        expiringSoonDays: DEFAULT_EXPIRING_SOON_DAYS,
        createdBy: rng.pick(OPERATORS),
        checksum: rng.hex(32),
        effectiveFrom: iso(nowMs - rng.int(30, 240) * DAY),
        effectiveTo: null,
        defaultPolicy: reasonCode === null,
      });
    }
  }

  /* ---- audit chain ---- */
  let previousHash: string | null = null;
  let sequenceNo = 0;
  const auditSources: { entityType: AggregateType; entityId: string; merchantId: string; action: string; at: string }[] = [
    ...cases.map((item) => ({
      entityType: 'CASE' as AggregateType,
      entityId: item.caseId,
      merchantId: item.merchantId,
      action: `CASE_${item.status}`,
      at: item.updatedAt,
    })),
    ...disputes.map((item) => ({
      entityType: 'DISPUTE' as AggregateType,
      entityId: item.disputeId,
      merchantId: item.merchantId,
      action: 'DISPUTE_CREATED',
      at: item.openedAt,
    })),
    ...Object.values(evidenceById)
      .slice(0, 60)
      .map((item) => ({
        entityType: 'EVIDENCE' as AggregateType,
        entityId: item.evidenceId,
        merchantId: item.merchantId,
        action: 'EVIDENCE_ADDED',
        at: item.createdAt,
      })),
  ].sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime());

  for (const source of auditSources) {
    sequenceNo += 1;
    const hash = rng.hex(64);
    auditEvents.push({
      auditId: sequentialId('AUD-', sequenceNo),
      sequenceNo,
      entityType: source.entityType,
      entityId: source.entityId,
      merchantId: source.merchantId,
      action: source.action,
      actor: source.action.startsWith('CASE') ? 'case-orchestrator-service' : 'state-builder-worker',
      actorType: 'SYSTEM',
      occurredAt: source.at,
      correlationId: seededUuid(rng),
      causationId: null,
      sourceEventId: seededUuid(rng),
      before: null,
      after: { action: source.action },
      previousHash,
      hash,
    });
    previousHash = hash;
  }

  /* ---- per-merchant summaries and funnels, aggregated from what was generated ---- */
  for (const seedRow of MERCHANT_SEEDS) {
    const built = builtByMerchant[seedRow.merchantId] ?? [];
    const merchantEvidence = built.flatMap((item) => item.evidence);
    const merchantDisputes = disputes.filter((item) => item.merchantId === seedRow.merchantId);
    const merchantCases = cases.filter((item) => item.merchantId === seedRow.merchantId);
    const merchantGaps = gapsByMerchant[seedRow.merchantId] ?? [];

    const distribution = { READY: 0, NEARLY_READY: 0, AT_RISK: 0, NOT_READY: 0 };
    let scoreTotal = 0;
    for (const item of built) {
      distribution[item.readiness.band] += 1;
      scoreTotal += item.readiness.score;
    }

    const byEvidenceStatus = {
      PENDING: 0,
      ACTIVE: 0,
      EXPIRING: 0,
      EXPIRED: 0,
      INVALIDATED: 0,
      SUPERSEDED: 0,
    };
    for (const item of merchantEvidence) byEvidenceStatus[item.status] += 1;

    const byCaseStatus = {
      CREATED: 0,
      ASSEMBLING: 0,
      INVESTIGATING: 0,
      AWAITING_EVIDENCE: 0,
      AWAITING_APPROVAL: 0,
      PREPARED: 0,
      SUBMITTED: 0,
      CLOSED: 0,
      FAILED: 0,
    };
    for (const item of merchantCases) byCaseStatus[item.status] += 1;

    const bySeverity = { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 };
    for (const item of merchantGaps) bySeverity[item.severity] += 1;

    const openDisputes = merchantDisputes.filter(
      (item) => !['WON', 'LOST', 'EXPIRED', 'WITHDRAWN'].includes(item.status),
    );
    const averageScore = built.length === 0 ? 0 : Math.round(scoreTotal / built.length);
    const merchantInvestigations = Object.values(investigationsById).filter(
      (item) => item.merchantId === seedRow.merchantId,
    );

    summaries[seedRow.merchantId] = {
      merchantId: seedRow.merchantId,
      displayName: seedRow.displayName,
      defaultCurrency: seedRow.currency,
      transactions: built.length,
      averageReadinessScore: built.length === 0 ? null : averageScore,
      dominantBand: built.length === 0 ? null : (bandFromScore(averageScore) ?? 'NOT_READY'),
      readinessDistribution: distribution,
      evidenceByStatus: byEvidenceStatus,
      casesByStatus: byCaseStatus,
      openDisputes: openDisputes.length,
      atRiskTransactions: distribution.AT_RISK + distribution.NOT_READY,
      expiringEvidence: byEvidenceStatus.EXPIRING,
      casesRequiringReview: byCaseStatus.AWAITING_APPROVAL,
      blockingGaps: bySeverity.HIGH + bySeverity.CRITICAL,
      generatedAt: iso(nowMs),
    };

    const events = built.length * rng.int(9, 16);
    const candidates = merchantDisputes.length;
    const ambiguous = Math.max(1, Math.round(candidates * 0.55));
    const aiInvestigated = merchantInvestigations.filter((item) => item.provider !== 'deterministic').length;
    funnelByMerchant[seedRow.merchantId] = {
      merchantId: seedRow.merchantId,
      from: iso(nowMs - 30 * DAY),
      to: iso(nowMs),
      events,
      candidates,
      ambiguous,
      aiInvestigated,
      humanReviewed: merchantCases.filter((item) => item.status === 'AWAITING_APPROVAL').length,
      autoPrepared: merchantInvestigations.filter(
        (item) => item.provider === 'deterministic' && item.recommendedAction === 'PREPARE_REPRESENTMENT',
      ).length,
      denied: merchantInvestigations.filter((item) => item.safetyDecision === 'DENY').length,
    };
  }

  /* ---- simulation console fixtures ---- */
  const simulationRuns: SimulationRun[] = [
    {
      runId: 'SIM-000001',
      seed: 42,
      merchants: 4,
      transactions: 500,
      days: 30,
      disputeRateBps: 300,
      failureProfile: 'NONE',
      scenarioKey: null,
      status: 'COMPLETED',
      progressPercent: 100,
      eventsPlanned: 6_412,
      eventsEmitted: 6_412,
      transactionsCreated: 500,
      evidenceCreated: 2_180,
      disputesCreated: 15,
      createdAt: iso(nowMs - 4 * HOUR),
      startedAt: iso(nowMs - 4 * HOUR),
      finishedAt: iso(nowMs - 3.4 * HOUR),
      requestedBy: 'ops.mistry',
      errorMessage: null,
      params: { seed: 42, merchants: 4, transactions: 500 },
    },
    {
      runId: 'SIM-000002',
      seed: 1337,
      merchants: 2,
      transactions: 120,
      days: 7,
      disputeRateBps: 900,
      failureProfile: 'LATE_EVENTS',
      scenarioKey: 'late-delivery-proof',
      status: 'RUNNING',
      progressPercent: 64,
      eventsPlanned: 1_004,
      eventsEmitted: 1_004,
      transactionsCreated: 77,
      evidenceCreated: 331,
      disputesCreated: 6,
      createdAt: iso(nowMs - 12 * 60_000),
      startedAt: iso(nowMs - 12 * 60_000),
      finishedAt: null,
      requestedBy: 'ops.tanaka',
      errorMessage: null,
      params: { seed: 1337, merchants: 2, transactions: 120, failureProfile: 'LATE_EVENTS' },
    },
    {
      runId: 'SIM-000003',
      seed: 7,
      merchants: 1,
      transactions: 50,
      days: 1,
      disputeRateBps: 2_500,
      failureProfile: 'DUPLICATES',
      scenarioKey: 'duplicate-storm',
      status: 'STOPPED',
      progressPercent: 38,
      eventsPlanned: 402,
      eventsEmitted: 402,
      transactionsCreated: 19,
      evidenceCreated: 74,
      disputesCreated: 4,
      createdAt: iso(nowMs - 2 * DAY),
      startedAt: iso(nowMs - 2 * DAY),
      finishedAt: iso(nowMs - 2 * DAY + 9 * 60_000),
      requestedBy: 'risk.almeida',
      errorMessage: 'stopped by operator',
      params: { seed: 7, merchants: 1, transactions: 50 },
    },
  ];

  const chaosInjections: ChaosInjection[] = [
    {
      injectionId: 'CHA-000001',
      runId: 'SIM-000002',
      merchantId: 'MER-0001',
      type: 'DUPLICATE_EVENT',
      status: 'APPLIED',
      target: { transactionId: transactions[0]?.transactionId ?? 'TX-000001', count: 5 },
      delayMs: null,
      eventCount: 5,
      actor: 'ops.mistry',
      injectedAt: iso(nowMs - 26 * 60_000),
      completedAt: iso(nowMs - 26 * 60_000 + 1_200),
      result: { duplicatesRejected: 5, stateChanged: false },
      errorMessage: null,
    },
    {
      injectionId: 'CHA-000002',
      runId: 'SIM-000002',
      merchantId: 'MER-0001',
      type: 'DELETE_EVIDENCE',
      status: 'APPLIED',
      target: { evidenceId: Object.keys(evidenceById)[3] ?? 'EV-000004' },
      delayMs: null,
      eventCount: 1,
      actor: 'ops.mistry',
      injectedAt: iso(nowMs - 18 * 60_000),
      completedAt: iso(nowMs - 18 * 60_000 + 800),
      result: { readinessDelta: -18, gapCreated: 'MISSING' },
      errorMessage: null,
    },
    {
      injectionId: 'CHA-000003',
      runId: null,
      merchantId: 'MER-0002',
      type: 'OUT_OF_ORDER_EVENT',
      status: 'APPLIED',
      target: { transactionId: transactions[1]?.transactionId ?? 'TX-000002' },
      delayMs: 45_000,
      eventCount: 3,
      actor: 'ops.tanaka',
      injectedAt: iso(nowMs - 9 * 60_000),
      completedAt: iso(nowMs - 9 * 60_000 + 400),
      result: { finalStateConsistent: true },
      errorMessage: null,
    },
    {
      injectionId: 'CHA-000004',
      runId: null,
      merchantId: 'MER-0003',
      type: 'CORRUPT_EVIDENCE_HASH',
      status: 'APPLIED',
      target: { evidenceId: Object.keys(evidenceById)[9] ?? 'EV-000010' },
      delayMs: null,
      eventCount: 1,
      actor: 'risk.almeida',
      injectedAt: iso(nowMs - 5 * 60_000),
      completedAt: iso(nowMs - 5 * 60_000 + 600),
      result: { integrityCheck: 'FAILED', evidenceStatus: 'INVALIDATED' },
      errorMessage: null,
    },
    {
      injectionId: 'CHA-000005',
      runId: null,
      merchantId: 'MER-0001',
      type: 'KILL_WORKER',
      status: 'APPLIED',
      target: { service: 'readiness-worker' },
      delayMs: null,
      eventCount: null,
      actor: 'ops.mistry',
      injectedAt: iso(nowMs - 3 * 60_000),
      completedAt: iso(nowMs - 3 * 60_000 + 15_000),
      result: { lagPeak: 812, recoveredInSeconds: 14 },
      errorMessage: null,
    },
    {
      injectionId: 'CHA-000006',
      runId: null,
      merchantId: 'MER-0004',
      type: 'INJECT_DISPUTE',
      status: 'REQUESTED',
      target: { transactionId: transactions[2]?.transactionId ?? 'TX-000003', reasonCode: 'FRAUDULENT_TRANSACTION' },
      delayMs: null,
      eventCount: 1,
      actor: 'ops.reyes',
      injectedAt: iso(nowMs - 40_000),
      completedAt: null,
      result: {},
      errorMessage: null,
    },
  ];

  // Shaped exactly like `GET /sim/v1/scenarios` (contract 8.5): the `expected` block is the
  // assertion target, and there is no `chaosTypes` - chaos is injected via `POST /chaos`.
  const scenarios: Scenario[] = [
    {
      key: 'late-delivery-proof',
      title: 'Late delivery proof',
      description:
        'A dispute is raised before the delivery proof arrives; the proof lands 40 seconds later out of order.',
      reasonCode: 'GOODS_NOT_RECEIVED',
      seed: 4101,
      merchants: 1,
      transactions: 8,
      days: 21,
      startAt: '2026-01-05T06:00:00Z',
      expected: {
        readinessBand: 'READY',
        scoreMin: 90,
        scoreMax: 100,
        gapTypes: [],
        aiPath: 'DETERMINISTIC',
        classification: 'DEFENDABLE',
        recommendedAction: 'PREPARE_REPRESENTMENT',
      },
      demoNote:
        'Readiness climbs from AT_RISK to READY and the case leaves AWAITING_EVIDENCE without a replay.',
    },
    {
      key: 'duplicate-storm',
      title: 'Duplicate storm',
      description: 'Every event on one transaction is redelivered five times.',
      reasonCode: 'GOODS_NOT_RECEIVED',
      seed: 4102,
      merchants: 1,
      transactions: 8,
      days: 21,
      startAt: '2026-01-05T06:00:00Z',
      expected: {
        readinessBand: 'READY',
        scoreMin: 90,
        scoreMax: 100,
        gapTypes: [],
        aiPath: 'DETERMINISTIC',
        classification: 'DEFENDABLE',
        recommendedAction: 'PREPARE_REPRESENTMENT',
      },
      demoNote:
        'Duplicate counters rise, state does not move, and the readiness score is unchanged.',
    },
    {
      key: 'evidence-tamper',
      title: 'Evidence tamper',
      description: 'The stored object behind a mandatory artifact is corrupted after capture.',
      reasonCode: 'GOODS_NOT_RECEIVED',
      seed: 4103,
      merchants: 1,
      transactions: 8,
      days: 21,
      startAt: '2026-01-05T06:00:00Z',
      expected: {
        readinessBand: 'NOT_READY',
        scoreMin: 0,
        scoreMax: 45,
        gapTypes: ['UNVERIFIABLE_PROVENANCE', 'MISSING'],
        aiPath: 'DETERMINISTIC',
        classification: 'NOT_DEFENDABLE',
        recommendedAction: 'ACCEPT_LIABILITY',
      },
      demoNote:
        'Integrity verification fails, the artifact is INVALIDATED, and a CRITICAL gap appears.',
    },
    {
      key: 'worker-outage',
      title: 'Worker outage and recovery',
      description: 'The readiness worker is killed mid-stream and restarted.',
      reasonCode: 'GOODS_NOT_RECEIVED',
      seed: 4104,
      merchants: 1,
      transactions: 8,
      days: 21,
      startAt: '2026-01-05T06:00:00Z',
      expected: {
        readinessBand: 'READY',
        scoreMin: 90,
        scoreMax: 100,
        gapTypes: [],
        aiPath: 'DETERMINISTIC',
        classification: 'DEFENDABLE',
        recommendedAction: 'PREPARE_REPRESENTMENT',
      },
      demoNote:
        'Consumer lag spikes then drains; no event is lost and no score is recomputed twice.',
    },
  ];

  // `GET /transactions/{id}` answers with the nested TransactionDetailResponse, not the row.
  const transactionsById: Record<string, TransactionDetail> = {};
  for (const merchantBuilt of Object.values(builtByMerchant)) {
    for (const item of merchantBuilt) {
      transactionsById[item.detail.transactionId] = {
        transaction: item.detail,
        facts: item.facts,
        readiness: item.readiness,
        evidence: item.evidence,
        evidenceCount: item.evidence.length,
        openGapCount: item.gaps.length,
      };
    }
  }
  const disputesById: Record<string, DisputeView> = {};
  for (const item of disputes) disputesById[item.disputeId] = item;
  const casesById: Record<string, CaseView> = {};
  for (const item of cases) casesById[item.caseId] = item;
  const policiesById: Record<string, PolicyView> = {};
  for (const item of policies) policiesById[item.policyId] = item;

  for (const [caseId, xray] of Object.entries(xrayByCase)) {
    xray.auditEventIds = auditEvents
      .filter((item) => item.entityId === caseId)
      .map((item) => item.auditId);
  }

  return {
    generatedAt: iso(nowMs),
    seed,
    merchants,
    summaries,
    transactions,
    transactionsById,
    evidenceById,
    evidenceByTransaction,
    versionsByEvidence,
    lineageByEvidence,
    integrityByEvidence,
    readinessByTransaction,
    timelineByTransaction,
    graphByTransaction,
    gaps,
    gapsByMerchant,
    disputes,
    disputesById,
    cases,
    casesById,
    xrayByCase,
    packageByCase,
    investigationsById,
    policies,
    policiesById,
    auditEvents,
    funnelByMerchant,
    simulationRuns,
    chaosInjections,
    scenarios,
  };
}

/** The dataset the mock router serves. Built once per page load. */
export const mockDataset: MockDataset = buildMockDataset();
