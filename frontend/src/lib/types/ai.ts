/**
 * AI contract types - contract 9.1/9.2/9.3/9.4, mirrors `core.model.InvestigationContext`,
 * `core.model.InvestigationResult`, `core.model.SafetyVerdict`, `core.ai.AdmissionDecision`.
 *
 * The UI only ever *reads* these. AI never mutates financial state (contract 17 rule 2), so
 * there is no client-side mutation that submits an InvestigationResult.
 */
import type { Iso8601, Money } from './common';
import type { EvidenceType, EvidenceView } from './evidence';
import type { ContradictionView, ReadinessGap, RequirementView } from './readiness';
import type { DisputeReasonCode } from './dispute';
import type { PolicyConstraints } from './policy';
import type { TimelineEntry } from './events';

export type InvestigationClassification =
  | 'DEFENDABLE'
  | 'WEAK'
  | 'INDEFENSIBLE'
  | 'INSUFFICIENT_EVIDENCE'
  | 'AMBIGUOUS';

export const INVESTIGATION_CLASSIFICATIONS: readonly InvestigationClassification[] = [
  'DEFENDABLE',
  'WEAK',
  'INDEFENSIBLE',
  'INSUFFICIENT_EVIDENCE',
  'AMBIGUOUS',
] as const;

export type RecommendedAction =
  | 'PREPARE_REPRESENTMENT'
  | 'GATHER_MORE_EVIDENCE'
  | 'ACCEPT_LIABILITY'
  | 'ESCALATE_TO_HUMAN'
  | 'REQUEST_POLICY_REVIEW';

export const RECOMMENDED_ACTIONS: readonly RecommendedAction[] = [
  'PREPARE_REPRESENTMENT',
  'GATHER_MORE_EVIDENCE',
  'ACCEPT_LIABILITY',
  'ESCALATE_TO_HUMAN',
  'REQUEST_POLICY_REVIEW',
] as const;

export type SafetyDecision = 'ALLOW' | 'ALLOW_WITH_REVIEW' | 'DENY';

export const SAFETY_DECISIONS: readonly SafetyDecision[] = [
  'ALLOW',
  'ALLOW_WITH_REVIEW',
  'DENY',
] as const;

/** One evidence-backed claim - mirrors `core.model.Citation`. */
export interface Citation {
  claim: string;
  evidenceId: string;
}

/** Provider/latency/token metadata - mirrors `core.model.ModelMetadata`. */
export interface ModelMetadata {
  /** `gemini`, `mock`, `null`, or `deterministic` when the non-AI path produced the result. */
  provider: string;
  model: string;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  attempt: number;
}

export const DETERMINISTIC_PROVIDER = 'deterministic';

export function isDeterministicResult(metadata: ModelMetadata | null | undefined): boolean {
  return metadata?.provider === DETERMINISTIC_PROVIDER;
}

/** Merchant history term of the context - mirrors `core.model.HistoricalContext`. */
export interface HistoricalContext {
  /** Win rate in [0,1]. */
  merchantWinRate: number;
  similarCases: number;
}

/** Contract 9.1 - mirrors `core.model.InvestigationContext`. */
export interface InvestigationContext {
  investigationId: string;
  caseId: string;
  disputeId: string;
  merchantId: string;
  transactionId: string;
  reasonCode: DisputeReasonCode;
  disputeAmount: Money;
  deadlineAt: Iso8601 | null;
  transactionSummary: Record<string, unknown>;
  evidence: EvidenceView[];
  requirements: RequirementView[];
  gaps: ReadinessGap[];
  contradictions: ContradictionView[];
  policyConstraints: PolicyConstraints;
  timeline: TimelineEntry[];
  historicalContext: HistoricalContext;
}

/** Contract 9.2 - mirrors `core.model.InvestigationResult`. Schema-constrained on both sides. */
export interface InvestigationResult {
  investigationId: string;
  classification: InvestigationClassification;
  /** Model confidence in [0,1]. */
  confidence: number;
  supportingEvidence: string[];
  /** Evidence TYPES, not ids - missing evidence has no id yet. Constrained by the referee schema. */
  missingEvidence: EvidenceType[];
  contradictions: ContradictionView[];
  reasoningSummary: string;
  narrative: string;
  recommendedAction: RecommendedAction;
  citations: Citation[];
  modelMetadata: ModelMetadata;
}

/** Contract 9.3 outcome - mirrors `core.model.SafetyVerdict`. */
export interface SafetyVerdict {
  decision: SafetyDecision;
  reasons: string[];
  /** Claims whose evidence could not be verified; drives `pdei_ai_unsupported_claims_total`. */
  unsupportedClaims: string[];
}

/**
 * Contract 9.4 short-circuits - mirrors `core.ai.ShortCircuit` and
 * `pdei_ai.models.admission.ShortCircuit`. Member order and spelling are identical in all three.
 *
 * `NONE` is not a placeholder for "absent": the backend always emits it for an admitted case,
 * meaning "no short-circuit applied, the priority formula decided". Test for it explicitly
 * (`shortCircuit !== 'NONE'`) rather than for truthiness.
 */
export type ShortCircuit =
  | 'NONE'
  | 'ALL_REQUIREMENTS_SATISFIED'
  | 'NO_EVIDENCE'
  | 'PAST_DEADLINE'
  | 'BELOW_PRIORITY_THRESHOLD'
  | 'RATE_LIMITED'
  | 'BUDGET_EXHAUSTED'
  | 'PROVIDER_UNAVAILABLE';

export const SHORT_CIRCUITS: readonly ShortCircuit[] = [
  'NONE',
  'ALL_REQUIREMENTS_SATISFIED',
  'NO_EVIDENCE',
  'PAST_DEADLINE',
  'BELOW_PRIORITY_THRESHOLD',
  'RATE_LIMITED',
  'BUDGET_EXHAUSTED',
  'PROVIDER_UNAVAILABLE',
] as const;

/** The short-circuit worth showing, or null when none applied. Never renders `NONE` as a reason. */
export function activeShortCircuit(
  shortCircuit: ShortCircuit | null | undefined,
): ShortCircuit | null {
  return shortCircuit && shortCircuit !== 'NONE' ? shortCircuit : null;
}

/** Contract 9.4 - mirrors `core.ai.AdmissionDecision`. */
export interface AdmissionDecision {
  admit: boolean;
  /** 0-100 priority from the contract 9.4 formula. */
  priority: number;
  reason: string;
  /** Always present: the backend serialises `NONE` when the priority formula decided. */
  shortCircuit: ShortCircuit;
  deterministicAction: RecommendedAction | null;
  financialImpact: number;
  deadlineUrgency: number;
  ambiguityScore: number;
  deterministicConfidence: number;
}

/**
 * `GET /investigations/{investigationId}` - the stored investigation: what the model proposed
 * and what the deterministic gate did with it. Mirrors `core.spi.InvestigationRecord` plus the
 * decoded result/verdict the gateway attaches for the UI.
 */
export interface InvestigationRecord {
  investigationId: string;
  caseId: string;
  disputeId: string;
  merchantId: string;
  transactionId: string;
  classification: InvestigationClassification;
  confidence: number;
  recommendedAction: RecommendedAction;
  safetyDecision: SafetyDecision;
  provider: string;
  model: string;
  latencyMs: number;
  promptTokens: number;
  completionTokens: number;
  attempt: number;
  reasoningSummary: string;
  narrative: string;
  /** Decoded contract 9.2 payload. Null when the case was resolved deterministically. */
  result: InvestigationResult | null;
  verdict: SafetyVerdict | null;
  admission: AdmissionDecision | null;
  startedAt: Iso8601;
  completedAt: Iso8601 | null;
}
