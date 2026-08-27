/**
 * Policy domain types - contract 8.1 policies routes, mirrors `core.policy.PolicyView`,
 * `core.policy.RequirementSpec`, `core.policy.PolicyDraft`, `core.policy.PolicyDecision`.
 */
import type { Iso8601 } from './common';
import type { EvidenceType } from './evidence';
import type { RequirementStrength } from './readiness';
import type { DisputeReasonCode } from './dispute';
import type { RecommendedAction } from './ai';

/** One requirement row of a policy version - mirrors `core.policy.RequirementSpec`. */
export interface RequirementSpec {
  type: EvidenceType;
  strength: RequirementStrength;
  /** Scoring weight; defaults to the strength weight but a merchant policy may override it. */
  weight: number;
  /** Evidence older than this no longer satisfies the requirement; null means it never expires. */
  maxAgeDays: number | null;
  provenanceRequired: boolean;
  /** Quality floor in [0,1]. */
  minQualityScore: number;
  note: string | null;
}

/**
 * An immutable policy version - mirrors `core.policy.PolicyView`.
 * `humanReviewAboveAmountMinor` + `currency` are the money pair; never a float.
 */
export interface PolicyView {
  policyId: string;
  policyVersionId: string;
  version: number;
  merchantId: string;
  reasonCode: DisputeReasonCode | null;
  requirements: RequirementSpec[];
  permittedActions: RecommendedAction[];
  prohibitedEvidenceTypes: EvidenceType[];
  autoPrepareMinConfidence: number;
  maxContradictions: number;
  minReadinessScoreForAutoPrepare: number;
  humanReviewAboveAmountMinor: number;
  currency: string;
  autoSubmitEnabled: boolean;
  responseWindowDays: number;
  expiringSoonDays: number;
  createdBy: string;
  checksum: string;
  effectiveFrom: Iso8601;
  effectiveTo: Iso8601 | null;
  defaultPolicy: boolean;
}

/** Body of `PUT /policies/{policyId}` - mirrors `core.policy.PolicyDraft`. */
export interface PolicyDraft {
  merchantId: string;
  reasonCode: DisputeReasonCode | null;
  requirements: RequirementSpec[];
  permittedActions: RecommendedAction[];
  prohibitedEvidenceTypes: EvidenceType[];
  autoPrepareMinConfidence: number;
  maxContradictions: number;
  minReadinessScoreForAutoPrepare: number;
  humanReviewAboveAmountMinor: number;
  currency: string;
  autoSubmitEnabled: boolean;
  responseWindowDays: number;
  expiringSoonDays: number;
}

/**
 * Body of `GET /requirements?reasonCode=&merchantId=` and
 * `GET /policies/{policyId}/requirements` - mirrors `api.dto.RequirementsResponse` field for
 * field. Both routes return this envelope, never a bare `RequirementSpec[]`.
 *
 * `policyVersionId` names which version answered and `defaultPolicy` says whether that was the
 * merchant's published matrix or the seeded platform default - the two carry very different
 * weight when a decision is questioned later. `reasonCode` is null for a merchant baseline
 * profile (the union of MANDATORY requirements across the reason codes that merchant receives).
 */
export interface RequirementsResponse {
  merchantId: string | null;
  reasonCode: DisputeReasonCode | null;
  policyId: string | null;
  policyVersionId: string | null;
  defaultPolicy: boolean;
  requirements: RequirementSpec[];
  /** Count of MANDATORY rows in `requirements`; computed server-side. */
  mandatoryCount: number;
}

/** Result of evaluating one proposed action - mirrors `core.policy.PolicyDecision`. */
export interface PolicyDecision {
  permitted: boolean;
  action: RecommendedAction;
  policyVersionId: string;
  reasons: string[];
}

/** Projection handed to the AI service - mirrors `core.model.PolicyConstraints` (contract 9.1). */
export interface PolicyConstraints {
  autoPrepareMinConfidence: number;
  maxContradictions: number;
  prohibitedEvidenceTypes: EvidenceType[];
}

/**
 * Query shape of `GET /policies`. `merchantId` is required: `PolicyController.list` declares it
 * as a required request param and answers 400 without it. The route returns a bare
 * `PolicyView[]` - it is not paged, so there is no page/size here.
 */
export interface PolicyQuery {
  merchantId: string;
}
