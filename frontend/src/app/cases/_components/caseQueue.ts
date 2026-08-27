/**
 * Case-queue row assembly.
 *
 * `GET /cases` (contract 8.1) returns `CaseView`, which carries workflow state but not the
 * money, the reason code or the deadline - those live on the dispute, and readiness lives on
 * the transaction. The queue therefore joins three merchant-scoped list calls client-side.
 * The join is pure and lives here so the board component stays presentational.
 *
 * Known limitation (see frontend/context.md): the readiness shown on a card is the
 * transaction's *latest* snapshot, not the snapshot captured when the dispute landed. The
 * at-dispute snapshot is only available on `GET /cases/{caseId}/xray`, one call per case.
 */
import type { Iso8601, Money } from '@/lib/types/common';
import type { CaseStatus, CaseView } from '@/lib/types/case';
import type { DisputeReasonCode, DisputeView } from '@/lib/types/dispute';
import type { ReadinessBand } from '@/lib/types/readiness';
import type { TransactionView } from '@/lib/types/transaction';

/** One card in the queue: the case plus the dispute and readiness context it needs. */
export interface CaseQueueRow {
  caseId: string;
  disputeId: string;
  merchantId: string;
  transactionId: string;
  status: CaseStatus;
  workflowId: string | null;
  assignedTo: string | null;
  packageVersion: number;
  openedAt: Iso8601;
  updatedAt: Iso8601;
  /** From the joined dispute; null when the dispute row was not in the page we fetched. */
  amountAtRisk: Money | null;
  reasonCode: DisputeReasonCode | null;
  deadlineAt: Iso8601 | null;
  /** Latest readiness snapshot of the disputed transaction. */
  readinessScore: number | null;
  readinessBand: ReadinessBand | null;
  readinessComputedAt: Iso8601 | null;
  /** The Temporal workflow is parked on `humanDecision` (contract 10, step 8). */
  awaitingHuman: boolean;
  /** Terminal failure: nothing moves until an operator looks at it. */
  failed: boolean;
}

/**
 * Statuses where the workflow cannot progress without a person.
 * `AWAITING_APPROVAL` parks on the `humanDecision` signal; `FAILED` parks on an operator.
 */
export const HUMAN_BLOCKED_STATUSES: readonly CaseStatus[] = ['AWAITING_APPROVAL', 'FAILED'];

export function isAwaitingHuman(status: CaseStatus): boolean {
  return status === 'AWAITING_APPROVAL';
}

export function buildQueueRows(
  cases: readonly CaseView[],
  disputes: readonly DisputeView[],
  transactions: readonly TransactionView[],
): CaseQueueRow[] {
  const disputeById = new Map<string, DisputeView>();
  for (const dispute of disputes) disputeById.set(dispute.disputeId, dispute);

  const transactionById = new Map<string, TransactionView>();
  for (const transaction of transactions) transactionById.set(transaction.transactionId, transaction);

  return cases.map((caseView) => {
    const dispute = disputeById.get(caseView.disputeId) ?? null;
    const transaction = transactionById.get(caseView.transactionId) ?? null;
    return {
      caseId: caseView.caseId,
      disputeId: caseView.disputeId,
      merchantId: caseView.merchantId,
      transactionId: caseView.transactionId,
      status: caseView.status,
      workflowId: caseView.workflowId,
      assignedTo: caseView.assignedTo,
      packageVersion: caseView.packageVersion,
      openedAt: caseView.openedAt,
      updatedAt: caseView.updatedAt,
      amountAtRisk: dispute?.amount ?? transaction?.amount ?? null,
      reasonCode: dispute?.reasonCode ?? null,
      deadlineAt: dispute?.deadlineAt ?? null,
      readinessScore: transaction?.readinessScore ?? null,
      readinessBand: transaction?.readinessBand ?? null,
      readinessComputedAt: transaction?.readinessComputedAt ?? null,
      awaitingHuman: isAwaitingHuman(caseView.status),
      failed: caseView.status === 'FAILED',
    };
  });
}

/**
 * Lane ordering: anything parked on a human first, then the nearest deadline, then the most
 * recently touched. A case with no deadline sorts after every case that has one.
 */
export function compareQueueRows(a: CaseQueueRow, b: CaseQueueRow): number {
  if (a.awaitingHuman !== b.awaitingHuman) return a.awaitingHuman ? -1 : 1;
  const aDeadline = a.deadlineAt ? Date.parse(a.deadlineAt) : Number.POSITIVE_INFINITY;
  const bDeadline = b.deadlineAt ? Date.parse(b.deadlineAt) : Number.POSITIVE_INFINITY;
  if (aDeadline !== bDeadline) return aDeadline - bDeadline;
  return b.updatedAt.localeCompare(a.updatedAt);
}

/** Groups rows into swimlanes keyed by `CaseStatus`, each lane already sorted. */
export function groupByLane(
  rows: readonly CaseQueueRow[],
  lanes: readonly CaseStatus[],
): Map<CaseStatus, CaseQueueRow[]> {
  const grouped = new Map<CaseStatus, CaseQueueRow[]>();
  for (const lane of lanes) grouped.set(lane, []);
  for (const row of rows) {
    const bucket = grouped.get(row.status);
    if (bucket) bucket.push(row);
    else grouped.set(row.status, [row]);
  }
  for (const bucket of grouped.values()) bucket.sort(compareQueueRows);
  return grouped;
}

/** Total amount at risk in one lane, per currency (never summed across currencies). */
export function laneExposure(rows: readonly CaseQueueRow[]): Map<string, number> {
  const totals = new Map<string, number>();
  for (const row of rows) {
    if (!row.amountAtRisk) continue;
    const currency = row.amountAtRisk.currency;
    totals.set(currency, (totals.get(currency) ?? 0) + row.amountAtRisk.amountMinor);
  }
  return totals;
}
