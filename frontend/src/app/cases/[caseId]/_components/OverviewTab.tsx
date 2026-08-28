'use client';

import Link from 'next/link';
import { AlertTriangle, ExternalLink, Scale, ShieldQuestion } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { CopyableId } from '@/components/shared/CopyableId';
import { DeadlineCountdown } from '@/components/shared/DeadlineCountdown';
import { MoneyDisplay } from '@/components/shared/MoneyDisplay';
import { ReadinessMeter } from '@/components/shared/ReadinessMeter';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { StatTile } from '@/components/shared/StatTile';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { deadlineState, formatInstant } from '@/lib/format/date';
import { humanizeEnum } from '@/lib/format/id';
import type { CaseView, CaseXRay } from '@/lib/types/case';
import { WorkflowStepper } from './WorkflowStepper';

export interface OverviewTabProps {
  xray: CaseXRay;
  caseView: CaseView | undefined;
  bypassedAi: boolean;
}

/** Dispute facts, deadline, exposure, current state and the twelve-step workflow position. */
export function OverviewTab({ xray, caseView, bypassedAi }: OverviewTabProps) {
  const deadline = deadlineState(xray.deadlineAt);
  const unsatisfiedMandatory = (xray.readiness?.requirements ?? []).filter(
    (requirement) => requirement.strength === 'MANDATORY' && !requirement.satisfied,
  );

  return (
    <div className="space-y-5">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Amount at risk"
          value={<MoneyDisplay money={xray.disputeAmount} />}
          hint={`Disputed under ${humanizeEnum(xray.reasonCode)}.`}
          icon={Scale}
        />
        <StatTile
          label="Deadline"
          value={<DeadlineCountdown deadlineAt={xray.deadlineAt} variant="block" />}
          tone={!deadline ? 'neutral' : deadline.passed ? 'critical' : deadline.urgent ? 'warning' : 'neutral'}
          hint={
            xray.deadlineAt
              ? 'Contract 9.4 treats anything inside 48 hours as urgent.'
              : 'The network gave no response window on this dispute.'
          }
        />
        <StatTile
          label="Case status"
          value={<StatusBadge kind="case" value={xray.caseStatus} />}
          hint={`Dispute is ${humanizeEnum(xray.disputeStatus)}.`}
        />
        <StatTile
          label="Package version"
          value={caseView ? caseView.packageVersion : '-'}
          hint={
            xray.packageManifest
              ? `Bundle generated ${formatInstant(xray.packageManifest.generatedAt)}.`
              : 'No representment package has been assembled yet.'
          }
        />
      </div>

      {unsatisfiedMandatory.length > 0 ? (
        <Alert variant="warning">
          <AlertTriangle aria-hidden />
          <AlertTitle>
            {unsatisfiedMandatory.length} mandatory requirement
            {unsatisfiedMandatory.length === 1 ? '' : 's'} unsatisfied
          </AlertTitle>
          <AlertDescription>
            {unsatisfiedMandatory.map((requirement) => humanizeEnum(requirement.type)).join(', ')}.
            A DEFENDABLE classification with an unsatisfied mandatory requirement is rejected by
            the validator (contract 9.3 rule 7).
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="space-y-4">
          <section className="surface-card p-4" aria-label="Dispute facts">
            <h2 className="text-sm font-semibold text-foreground">Dispute facts</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              Everything below is deterministic platform state. No part of it was produced by a
              model (contract 17, rules 1 and 2).
            </p>
            <dl className="mt-3 grid grid-cols-1 gap-x-6 gap-y-2.5 text-sm sm:grid-cols-2">
              <Fact label="Case">
                <CopyableId id={xray.caseId} link={false} />
              </Fact>
              <Fact label="Dispute">
                <CopyableId id={xray.disputeId} />
              </Fact>
              <Fact label="Transaction">
                <CopyableId id={xray.transactionId} />
              </Fact>
              <Fact label="Merchant">
                <span className="mono-id">{xray.merchantId}</span>
              </Fact>
              <Fact label="Reason code">
                <Badge variant="outline">{humanizeEnum(xray.reasonCode)}</Badge>
              </Fact>
              <Fact label="Dispute status">
                <StatusBadge kind="dispute" value={xray.disputeStatus} />
              </Fact>
              <Fact label="Opened">
                <TimestampDisplay value={caseView?.openedAt} />
              </Fact>
              <Fact label="Last update">
                <TimestampDisplay value={caseView?.updatedAt ?? xray.generatedAt} />
              </Fact>
              <Fact label="Assigned to">
                <span className="text-foreground">{caseView?.assignedTo ?? 'unassigned'}</span>
              </Fact>
              <Fact label="X-Ray generated">
                <TimestampDisplay value={xray.generatedAt} mode="absolute" />
              </Fact>
            </dl>

            <div className="mt-3 flex flex-wrap gap-2 border-t border-border pt-3">
              <Link
                href={`/transactions/${xray.transactionId}`}
                className="inline-flex items-center gap-1 text-xs text-primary underline-offset-4 hover:underline"
              >
                Open transaction <ExternalLink className="size-3" aria-hidden />
              </Link>
              <Link
                href={`/disputes/${xray.disputeId}`}
                className="inline-flex items-center gap-1 text-xs text-primary underline-offset-4 hover:underline"
              >
                Open dispute <ExternalLink className="size-3" aria-hidden />
              </Link>
            </div>
          </section>

          <WorkflowStepper
            status={xray.caseStatus}
            bypassedAi={bypassedAi}
            workflowId={caseView?.workflowId ?? null}
          />
        </div>

        <div className="space-y-4">
          <section className="surface-card p-4" aria-label="Readiness at this case">
            <h2 className="text-sm font-semibold text-foreground">Evidence readiness</h2>
            {xray.readiness ? (
              <>
                <ReadinessMeter
                  className="mt-3"
                  variant="hero"
                  score={xray.readiness.score}
                  band={xray.readiness.band}
                  caption={`Computed ${formatInstant(xray.readiness.computedAt)}`}
                />
                <dl className="mt-3 space-y-1.5 text-xs">
                  <ArithmeticRow label="Base score" value={xray.readiness.baseScore} />
                  <ArithmeticRow
                    label="Penalties"
                    value={-xray.readiness.penaltyPoints}
                    tone={xray.readiness.penaltyPoints > 0 ? 'var(--status-critical)' : undefined}
                  />
                  <ArithmeticRow label="Final" value={xray.readiness.score} emphasis />
                </dl>
                <p className="mt-2 text-2xs text-muted-foreground">
                  Contract 7 arithmetic, computed by <span className="mono-id">ReadinessEngine</span>{' '}
                  against policy version{' '}
                  <span className="mono-id">{xray.readiness.policyVersionId ?? 'unknown'}</span>.
                </p>
              </>
            ) : (
              <p className="mt-2 text-xs text-muted-foreground">
                No readiness snapshot is attached to this case. The readiness worker has not run
                for this transaction, or the snapshot expired from the cache.
              </p>
            )}
          </section>

          <section className="surface-card p-4" aria-label="Open gaps">
            <h2 className="text-sm font-semibold text-foreground">Open gaps</h2>
            {xray.gaps.length === 0 ? (
              <p className="mt-2 text-xs text-muted-foreground">
                No gaps detected. Every requirement in the applicable policy is satisfied by a
                usable artifact.
              </p>
            ) : (
              <ul className="mt-2 space-y-2">
                {xray.gaps.slice(0, 8).map((gap) => (
                  <li key={gap.gapId} className="flex items-start gap-2 text-xs">
                    <StatusBadge kind="severity" value={gap.severity} iconOnly />
                    <span className="min-w-0">
                      <span className="font-medium text-foreground">{humanizeEnum(gap.type)}</span>
                      {gap.evidenceType ? (
                        <span className="text-muted-foreground">
                          {' '}
                          · {humanizeEnum(gap.evidenceType)}
                        </span>
                      ) : null}
                      {gap.detail ? (
                        <span className="block text-muted-foreground">{gap.detail}</span>
                      ) : null}
                    </span>
                  </li>
                ))}
                {xray.gaps.length > 8 ? (
                  <li className="text-2xs text-muted-foreground">
                    +{xray.gaps.length - 8} more on the Evidence tab.
                  </li>
                ) : null}
              </ul>
            )}
          </section>

          <section className="surface-card p-4" aria-label="Contradictions">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
              <ShieldQuestion className="size-4 text-muted-foreground" aria-hidden />
              Contradictions
            </h2>
            {xray.contradictions.length === 0 ? (
              <p className="mt-2 text-xs text-muted-foreground">
                No cross-evidence conflicts. Zero contradictions is a precondition for the
                deterministic auto-prepare path (contract 9.4).
              </p>
            ) : (
              <ul className="mt-2 space-y-2 text-xs">
                {xray.contradictions.map((contradiction, index) => (
                  <li
                    key={`${contradiction.left ?? 'l'}-${contradiction.right ?? 'r'}-${index}`}
                    className="rounded-md border border-[color:var(--status-critical)]/30 bg-[color:var(--status-critical)]/5 p-2"
                  >
                    <span className="font-medium text-foreground">
                      {contradiction.field ?? 'field'} disagrees
                    </span>
                    <span className="mt-0.5 block text-muted-foreground">
                      {contradiction.detail ?? 'No detail supplied by the detector.'}
                    </span>
                    <span className="mono-id mt-1 block text-2xs text-muted-foreground">
                      {contradiction.left ?? '?'} ({contradiction.leftValue ?? '-'}) ·{' '}
                      {contradiction.right ?? '?'} ({contradiction.rightValue ?? '-'})
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-2xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 truncate text-sm text-foreground">{children}</dd>
    </div>
  );
}

function ArithmeticRow({
  label,
  value,
  emphasis = false,
  tone,
}: {
  label: string;
  value: number;
  emphasis?: boolean;
  tone?: string;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className={emphasis ? 'font-medium text-foreground' : 'text-muted-foreground'}>{label}</dt>
      <dd
        className={emphasis ? 'tabular font-semibold text-foreground' : 'tabular text-foreground'}
        style={tone ? { color: tone } : undefined}
      >
        {value}
      </dd>
    </div>
  );
}
