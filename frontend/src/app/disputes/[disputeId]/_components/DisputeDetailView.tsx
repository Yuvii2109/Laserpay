'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, ArrowUpRight, Briefcase, ClipboardList, Receipt } from 'lucide-react';
import {
  CopyableId,
  DeadlineCountdown,
  DetailList,
  EmptyState,
  ErrorState,
  LoadingState,
  MoneyDisplay,
  PageHeader,
  ReadinessBadge,
  ReadinessMeter,
  StatusBadge,
  TimestampDisplay,
  type DetailItem,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { casesApi, disputesApi, transactionsApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { isTerminalDispute } from '@/lib/types/dispute';
import { RequiredEvidenceChecklist } from './RequiredEvidenceChecklist';

export interface DisputeDetailViewProps {
  disputeId: string;
}

/** The gateway has no `GET /cases?disputeId=`, so the case is found in the merchant's page. */
const CASE_LOOKUP_PAGE_SIZE = 200;

/**
 * `/disputes/[disputeId]` (contract 14).
 *
 * A dispute is only interesting in company: the transaction it attacks, the case assembled to
 * defend it, the clock, and the evidence its reason code demands. All four are on this page so
 * the decision — defend or accept — can be made without navigating away.
 */
export function DisputeDetailView({ disputeId }: DisputeDetailViewProps) {
  const disputeQuery = useQuery({
    queryKey: queryKeys.disputes.detail(disputeId),
    queryFn: ({ signal }) => disputesApi.get(disputeId, signal),
  });

  const dispute = disputeQuery.data;
  const transactionId = dispute?.transactionId;
  const merchantId = dispute?.merchantId;

  const transactionQuery = useQuery({
    queryKey: queryKeys.transactions.detail(transactionId ?? ''),
    queryFn: ({ signal }) => transactionsApi.get(transactionId as string, signal),
    enabled: Boolean(transactionId),
  });

  const readinessQuery = useQuery({
    queryKey: queryKeys.transactions.readiness(transactionId ?? '', dispute?.reasonCode),
    queryFn: ({ signal }) =>
      transactionsApi.readiness(transactionId as string, dispute?.reasonCode, signal),
    enabled: Boolean(transactionId && dispute),
  });

  const caseListQuery = useQuery({
    queryKey: queryKeys.cases.list({ merchantId: merchantId ?? '', size: CASE_LOOKUP_PAGE_SIZE }),
    queryFn: ({ signal }) =>
      casesApi.list({ merchantId: merchantId as string, size: CASE_LOOKUP_PAGE_SIZE }, signal),
    enabled: Boolean(merchantId),
  });

  const linkedCase = React.useMemo(
    () => (caseListQuery.data?.content ?? []).find((item) => item.disputeId === disputeId) ?? null,
    [caseListQuery.data, disputeId],
  );

  if (disputeQuery.isLoading) {
    return (
      <div aria-busy="true">
        <LoadingState variant="panel" />
        <LoadingState variant="rows" count={6} className="mt-6" />
      </div>
    );
  }

  if (disputeQuery.isError || !dispute) {
    return (
      <>
        <PageHeader eyebrow={<BackLink />} title={disputeId} description="Dispute detail." />
        <ErrorState
          error={disputeQuery.error}
          onRetry={() => void disputeQuery.refetch()}
          title="Could not load this dispute"
        />
      </>
    );
  }

  const terminal = isTerminalDispute(dispute.status);
  // TransactionDetailResponse nests the row under `transaction`.
  const transactionDetail = transactionQuery.data;
  const transaction = transactionDetail?.transaction;

  const disputeItems: DetailItem[] = [
    {
      label: 'Disputed amount',
      value: <MoneyDisplay money={dispute.amount} className="text-base font-semibold" />,
      hint: transaction ? (
        <>
          of <MoneyDisplay money={transaction.amount} muted /> captured
        </>
      ) : undefined,
    },
    { label: 'Reason code', value: humanizeEnum(dispute.reasonCode) },
    { label: 'Status', value: <StatusBadge kind="dispute" value={dispute.status} /> },
    {
      label: 'Source',
      value: humanizeEnum(dispute.source),
      hint: dispute.networkCaseRef ? `Network ref ${dispute.networkCaseRef}` : undefined,
    },
    {
      label: 'Opened',
      value: <TimestampDisplay value={dispute.openedAt} mode="absolute" />,
      hint: (
        <>
          last updated <TimestampDisplay value={dispute.updatedAt} />
        </>
      ),
    },
    {
      label: 'Closed',
      value: dispute.closedAt ? (
        <TimestampDisplay value={dispute.closedAt} mode="absolute" />
      ) : (
        <span className="text-muted-foreground">Still open</span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow={<BackLink />}
        title={dispute.disputeId}
        description={`${humanizeEnum(dispute.reasonCode)} raised against ${dispute.transactionId}.`}
        meta={
          <>
            <StatusBadge kind="dispute" value={dispute.status} />
            <Badge variant="outline">
              <MoneyDisplay money={dispute.amount} />
            </Badge>
          </>
        }
        actions={
          <Button variant="outline" asChild>
            <Link href={`/transactions/${dispute.transactionId}`}>
              <Receipt className="size-4" aria-hidden />
              Open transaction
            </Link>
          </Button>
        }
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Card className="p-5">
          <h2 className="pb-4 text-sm font-semibold tracking-tight">Dispute</h2>
          <DetailList items={disputeItems} />
        </Card>

        <Card className="space-y-3 p-5">
          <h2 className="text-sm font-semibold tracking-tight">Response deadline</h2>
          {terminal ? (
            <p className="text-sm text-muted-foreground">
              This dispute reached <strong className="font-medium text-foreground">{humanizeEnum(dispute.status)}</strong>
              {dispute.closedAt ? (
                <>
                  {' '}
                  on <TimestampDisplay value={dispute.closedAt} mode="absolute" />
                </>
              ) : null}
              . There is nothing left to respond to.
            </p>
          ) : (
            <DeadlineCountdown deadlineAt={dispute.deadlineAt} variant="block" />
          )}
        </Card>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        {/* ---- linked transaction ---- */}
        <Card className="p-5">
          <div className="flex flex-wrap items-center justify-between gap-2 pb-4">
            <h2 className="text-sm font-semibold tracking-tight">Linked transaction</h2>
            <Link
              href={`/transactions/${dispute.transactionId}`}
              className="inline-flex items-center gap-1 text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              Open detail
              <ArrowUpRight className="size-3" aria-hidden />
            </Link>
          </div>

          {transactionQuery.isLoading ? (
            <LoadingState variant="text" count={4} />
          ) : transactionQuery.isError ? (
            <ErrorState
              error={transactionQuery.error}
              onRetry={() => void transactionQuery.refetch()}
              compact
            />
          ) : transaction ? (
            <div className="space-y-4">
              <DetailList
                columns={1}
                items={[
                  { label: 'Transaction', value: <CopyableId id={transaction.transactionId} /> },
                  {
                    label: 'Amount',
                    value: <MoneyDisplay money={transaction.amount} />,
                    hint: `${humanizeEnum(transaction.status)}${transaction.channel ? ` · ${humanizeEnum(transaction.channel)}` : ''}`,
                  },
                  {
                    label: 'Occurred',
                    value: <TimestampDisplay value={transaction.occurredAt} mode="absolute" />,
                    hint: (
                      <>
                        observed <TimestampDisplay value={transaction.observedAt} />
                      </>
                    ),
                  },
                  {
                    label: 'Evidence held',
                    value: `${transactionDetail?.evidenceCount ?? 0} artifact${(transactionDetail?.evidenceCount ?? 0) === 1 ? '' : 's'}`,
                    hint: `${transactionDetail?.openGapCount ?? 0} open gap${(transactionDetail?.openGapCount ?? 0) === 1 ? '' : 's'}`,
                  },
                ]}
              />
              <div className="border-t border-border pt-4">
                <ReadinessMeter
                  score={readinessQuery.data?.score ?? transaction.readinessScore}
                  band={readinessQuery.data?.band ?? transaction.readinessBand}
                  caption={
                    readinessQuery.data
                      ? `Scored against ${humanizeEnum(dispute.reasonCode)}`
                      : 'Latest snapshot for this transaction'
                  }
                />
              </div>
            </div>
          ) : null}
        </Card>

        {/* ---- linked case ---- */}
        <Card className="p-5">
          <h2 className="pb-4 text-sm font-semibold tracking-tight">Linked case</h2>

          {caseListQuery.isLoading ? (
            <LoadingState variant="text" count={3} />
          ) : caseListQuery.isError ? (
            <ErrorState
              error={caseListQuery.error}
              onRetry={() => void caseListQuery.refetch()}
              compact
            />
          ) : linkedCase ? (
            <div className="space-y-4">
              <DetailList
                columns={1}
                items={[
                  { label: 'Case', value: <CopyableId id={linkedCase.caseId} /> },
                  {
                    label: 'Status',
                    value: <StatusBadge kind="case" value={linkedCase.status} />,
                    hint: linkedCase.workflowId
                      ? `Temporal workflow ${linkedCase.workflowId}`
                      : 'No workflow id recorded',
                  },
                  {
                    label: 'Opened',
                    value: <TimestampDisplay value={linkedCase.openedAt} mode="absolute" />,
                    hint: (
                      <>
                        updated <TimestampDisplay value={linkedCase.updatedAt} />
                      </>
                    ),
                  },
                  {
                    label: 'Package version',
                    value: `v${linkedCase.packageVersion}`,
                    hint: linkedCase.assignedTo
                      ? `Assigned to ${linkedCase.assignedTo}`
                      : 'Unassigned',
                  },
                ]}
              />
              <Button variant="outline" size="sm" asChild>
                <Link href={`/cases/${linkedCase.caseId}`}>
                  <Briefcase className="size-3.5" aria-hidden />
                  Open the Case X-Ray
                </Link>
              </Button>
            </div>
          ) : (
            <EmptyState
              icon={Briefcase}
              title="No case assembled yet"
              description="case-orchestrator-service opens a DisputeCaseWorkflow when it consumes the DisputeCreated event. If this stays empty, check the orchestrator's consumer lag."
              compact
            />
          )}
        </Card>
      </div>

      {/* ---- required evidence for this reason code ---- */}
      <Card className="mt-4 p-5">
        <div className="flex flex-wrap items-center justify-between gap-2 pb-4">
          <h2 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
            <ClipboardList className="size-4 text-muted-foreground" aria-hidden />
            Required evidence for {humanizeEnum(dispute.reasonCode)}
          </h2>
          {readinessQuery.data ? (
            <ReadinessBadge
              band={readinessQuery.data.band}
              score={readinessQuery.data.score}
              size="sm"
            />
          ) : null}
        </div>

        {merchantId ? (
          <RequiredEvidenceChecklist
            reasonCode={dispute.reasonCode}
            merchantId={merchantId}
            snapshot={readinessQuery.data}
            snapshotLoading={readinessQuery.isLoading}
          />
        ) : null}
      </Card>
    </>
  );
}

function BackLink() {
  return (
    <Link
      href="/disputes"
      className="inline-flex items-center gap-1 underline-offset-4 hover:text-foreground hover:underline"
    >
      <ArrowLeft className="size-3" aria-hidden />
      All disputes
    </Link>
  );
}
