/**
 * Dispute case types - contract 6, 8.1 and 10, mirrors `core.model.CaseView`,
 * `core.model.CaseXRay`, `core.model.PackageManifest`.
 */
import type { Iso8601, Money } from './common';
import type { EvidenceGraph, EvidenceType, EvidenceView } from './evidence';
import type { ContradictionView, ReadinessBand, ReadinessGap, ReadinessSnapshot, RequirementStrength } from './readiness';
import type { DisputeReasonCode, DisputeStatus } from './dispute';
import type { InvestigationResult, SafetyVerdict } from './ai';
import type { TimelineEntry } from './events';

export type CaseStatus =
  | 'CREATED'
  | 'ASSEMBLING'
  | 'INVESTIGATING'
  | 'AWAITING_EVIDENCE'
  | 'AWAITING_APPROVAL'
  | 'PREPARED'
  | 'SUBMITTED'
  | 'CLOSED'
  | 'FAILED';

export const CASE_STATUSES: readonly CaseStatus[] = [
  'CREATED',
  'ASSEMBLING',
  'INVESTIGATING',
  'AWAITING_EVIDENCE',
  'AWAITING_APPROVAL',
  'PREPARED',
  'SUBMITTED',
  'CLOSED',
  'FAILED',
] as const;

/** Swimlane order for the case queue - workflow order of contract 10. */
export const CASE_STATUS_LANES: readonly CaseStatus[] = [
  'CREATED',
  'ASSEMBLING',
  'AWAITING_EVIDENCE',
  'INVESTIGATING',
  'AWAITING_APPROVAL',
  'PREPARED',
  'SUBMITTED',
  'CLOSED',
  'FAILED',
] as const;

/** `GET /cases` row - mirrors `core.model.CaseView`. */
export interface CaseView {
  caseId: string;
  disputeId: string;
  merchantId: string;
  transactionId: string;
  status: CaseStatus;
  /** Temporal workflow id, `case-{caseId}` (contract 10). */
  workflowId: string | null;
  assignedTo: string | null;
  packageVersion: number;
  openedAt: Iso8601;
  updatedAt: Iso8601;
  closedAt: Iso8601 | null;
}

/** One file inside a representment bundle - mirrors `core.model.PackageManifest.Item`. */
export interface PackageManifestItem {
  evidenceId: string;
  type: EvidenceType;
  strength: RequirementStrength;
  version: number;
  sha256: string;
  objectKey: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  /** Path of this file inside the zip. */
  entryPath: string;
  capturedAt: Iso8601;
}

/** `GET /cases/{caseId}/package` - mirrors `core.model.PackageManifest`. */
export interface PackageManifest {
  manifestId: string;
  caseId: string;
  disputeId: string;
  merchantId: string;
  transactionId: string;
  reasonCode: DisputeReasonCode;
  disputeAmount: Money;
  packageVersion: number;
  bundleObjectKey: string;
  bundleSha256: string;
  bundleSizeBytes: number;
  items: PackageManifestItem[];
  narrative: string;
  policyVersionId: string | null;
  readinessScore: number;
  readinessBand: ReadinessBand;
  generatedBy: string;
  generatedAt: Iso8601;
}

/**
 * `GET /cases/{caseId}/xray` - mirrors `core.model.CaseXRay`.
 * This single payload backs every tab of the Case X-Ray page
 * (timeline | graph | evidence | AI reasoning | gate | package).
 */
export interface CaseXRay {
  caseId: string;
  disputeId: string;
  transactionId: string;
  merchantId: string;
  caseStatus: CaseStatus;
  disputeStatus: DisputeStatus;
  reasonCode: DisputeReasonCode;
  disputeAmount: Money;
  deadlineAt: Iso8601 | null;
  readiness: ReadinessSnapshot | null;
  evidence: EvidenceView[];
  graph: EvidenceGraph | null;
  timeline: TimelineEntry[];
  gaps: ReadinessGap[];
  contradictions: ContradictionView[];
  /** Null when the case never went to the model (deterministic short-circuit). */
  investigation: InvestigationResult | null;
  safetyVerdict: SafetyVerdict | null;
  packageManifest: PackageManifest | null;
  auditEventIds: string[];
  generatedAt: Iso8601;
}

/** Query shape of `GET /cases`. */
export interface CaseQuery {
  merchantId?: string;
  status?: CaseStatus;
  page?: number;
  size?: number;
}

/** Body of `POST /cases/{caseId}/approve` and `/reject` - the Temporal `humanDecision` signal. */
export interface CaseDecisionRequest {
  actor: string;
  note?: string;
}

/** Body of `POST /cases/{caseId}/submit`. */
export interface CaseSubmitRequest {
  actor: string;
  /** Optional override of which package version is submitted; defaults to the latest. */
  packageVersion?: number;
}

/** Response of the three case commands: the new state plus the workflow it signalled. */
export interface CaseCommandResult {
  caseId: string;
  status: CaseStatus;
  workflowId: string | null;
  signal: string;
  acceptedAt: Iso8601;
}
