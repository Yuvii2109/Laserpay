'use client';

import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bomb, Crosshair, Zap } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from '@/components/ui/sonner';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import { JsonViewer } from '@/components/shared/JsonViewer';
import { simulationApi, transactionsApi } from '@/lib/api/endpoints';
import { newCorrelationId } from '@/lib/api/client';
import { queryKeys } from '@/lib/query/keys';
import { shortenId, humanizeEnum } from '@/lib/format/id';
import { useSelectedMerchantId } from '@/lib/store/uiStore';
import type { ChaosInjection, ChaosRequest } from '@/lib/types/simulation';
import {
  CHAOS_CATALOG,
  CHAOS_FAMILIES,
  CHAOS_FAMILY_BLURB,
  CHAOS_FAMILY_LABEL,
  CHAOS_SERVICES,
  KAFKA_TOPICS,
  chaosOfFamily,
  type ChaosSpec,
} from './chaosCatalog';

const TRANSACTION_FETCH_SIZE = 100;

export interface ChaosPanelProps {
  /** Attach injections to the run currently being watched, when there is one. */
  runId: string | null;
  onInjected: (injection: ChaosInjection) => void;
}

/**
 * One control per `ChaosType` in contract 6.
 *
 * Targets are picked once at the top and reused by every card, because the interesting
 * question is "what does this platform do to *this* transaction under each failure", not
 * "which of thirteen forms do I fill in". Every injection is confirmed, and the exact request
 * body is shown before it is sent - a chaos console that hides its payload is not a debugging
 * tool.
 */
export function ChaosPanel({ runId, onInjected }: ChaosPanelProps) {
  const merchantId = useSelectedMerchantId();
  const queryClient = useQueryClient();

  const [transactionId, setTransactionId] = React.useState<string>('');
  const [evidenceId, setEvidenceId] = React.useState<string>('');
  const [service, setService] = React.useState<string>(CHAOS_SERVICES[0] ?? 'readiness-worker');
  const [topic, setTopic] = React.useState<string>(KAFKA_TOPICS[1] ?? 'pdei.canonical.events.v1');
  const [delayMs, setDelayMs] = React.useState<number>(5_000);
  const [count, setCount] = React.useState<number>(3);
  const [pending, setPending] = React.useState<ChaosSpec | null>(null);

  const transactionsQuery = useQuery({
    queryKey: queryKeys.transactions.list({
      merchantId: merchantId ?? undefined,
      size: TRANSACTION_FETCH_SIZE,
    }),
    queryFn: ({ signal }) =>
      transactionsApi.list({ merchantId: merchantId ?? undefined, size: TRANSACTION_FETCH_SIZE }, signal),
    enabled: Boolean(merchantId),
  });

  const transactions = React.useMemo(
    () => transactionsQuery.data?.content ?? [],
    [transactionsQuery.data],
  );

  React.useEffect(() => {
    if (transactions.length === 0) {
      setTransactionId('');
      return;
    }
    if (!transactions.some((item) => item.transactionId === transactionId)) {
      setTransactionId(transactions[0]?.transactionId ?? '');
    }
  }, [transactions, transactionId]);

  const evidenceQuery = useQuery({
    queryKey: queryKeys.transactions.evidence(transactionId || 'none'),
    queryFn: ({ signal }) => transactionsApi.evidence(transactionId, signal),
    enabled: transactionId.length > 0,
  });

  const evidence = React.useMemo(() => evidenceQuery.data ?? [], [evidenceQuery.data]);

  React.useEffect(() => {
    if (evidence.length === 0) {
      setEvidenceId('');
      return;
    }
    if (!evidence.some((item) => item.evidenceId === evidenceId)) {
      setEvidenceId(evidence[0]?.evidenceId ?? '');
    }
  }, [evidence, evidenceId]);

  const buildRequest = React.useCallback(
    (spec: ChaosSpec): ChaosRequest => {
      const target: Record<string, unknown> = {};
      switch (spec.target) {
        case 'transaction':
          target[spec.targetKey] = transactionId;
          break;
        case 'evidence':
          target[spec.targetKey] = evidenceId;
          target['transactionId'] = transactionId;
          break;
        case 'service':
          target[spec.targetKey] = service;
          target['consumerGroup'] = `pdei-${service}`;
          break;
        case 'topic':
          target[spec.targetKey] = topic;
          break;
        default:
          break;
      }
      return {
        type: spec.type,
        target,
        ...(spec.usesDelay ? { delayMs } : {}),
        ...(spec.usesCount ? { count } : {}),
        ...(merchantId ? { merchantId } : {}),
        ...(runId ? { runId } : {}),
        actor: 'console',
      };
    },
    [transactionId, evidenceId, service, topic, delayMs, count, merchantId, runId],
  );

  const injectMutation = useMutation({
    mutationFn: (spec: ChaosSpec) =>
      simulationApi.injectChaos(buildRequest(spec), newCorrelationId()),
    onSuccess: (injection) => {
      onInjected(injection);
      void queryClient.invalidateQueries({ queryKey: queryKeys.simulation.chaos() });
      toast.success(`${humanizeEnum(injection.type)} injected`, {
        description: `${injection.injectionId} · watch the live event ticker`,
      });
    },
    onError: (error: Error) => toast.error('Injection failed', { description: error.message }),
  });

  const readiness = (spec: ChaosSpec): string | null => {
    if (spec.target === 'transaction' && !transactionId) return 'Select a transaction first.';
    if (spec.target === 'evidence' && !evidenceId) {
      return 'Select a transaction that has evidence first.';
    }
    return null;
  };

  return (
    <section className="space-y-4" aria-label="Chaos console">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Bomb className="size-4 text-muted-foreground" aria-hidden />
          Chaos console
        </h2>
        <span className="text-2xs text-muted-foreground">
          POST <span className="mono-id">/sim/v1/chaos</span> · every `ChaosType` in contract 6
        </span>
      </div>

      <div className="surface-card p-4">
        <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          <Crosshair className="size-3.5" aria-hidden />
          Target
        </h3>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          <div className="space-y-1.5">
            <Label htmlFor="chaos-transaction">Transaction</Label>
            <select
              id="chaos-transaction"
              value={transactionId}
              onChange={(event) => setTransactionId(event.target.value)}
              disabled={transactions.length === 0}
              className="h-9 w-full rounded-md border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
            >
              {transactions.length === 0 ? (
                <option value="">No transactions for this merchant</option>
              ) : (
                transactions.map((item) => (
                  <option key={item.transactionId} value={item.transactionId}>
                    {item.transactionId} · {item.readinessBand ?? 'unscored'}
                    {item.disputeId ? ' · disputed' : ''}
                  </option>
                ))
              )}
            </select>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="chaos-evidence">Evidence artifact</Label>
            <select
              id="chaos-evidence"
              value={evidenceId}
              onChange={(event) => setEvidenceId(event.target.value)}
              disabled={evidence.length === 0}
              className="h-9 w-full rounded-md border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
            >
              {evidence.length === 0 ? (
                <option value="">No evidence on the selected transaction</option>
              ) : (
                evidence.map((item) => (
                  <option key={item.evidenceId} value={item.evidenceId}>
                    {shortenId(item.evidenceId, 10, 4)} · {item.type} · {item.status}
                  </option>
                ))
              )}
            </select>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="chaos-service">Service / consumer</Label>
            <select
              id="chaos-service"
              value={service}
              onChange={(event) => setService(event.target.value)}
              className="h-9 w-full rounded-md border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {CHAOS_SERVICES.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
            <p className="mono-id text-2xs text-muted-foreground">group pdei-{service}</p>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="chaos-topic">Topic</Label>
            <select
              id="chaos-topic"
              value={topic}
              onChange={(event) => setTopic(event.target.value)}
              className="h-9 w-full rounded-md border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {KAFKA_TOPICS.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="chaos-delay">Delay (ms)</Label>
            <Input
              id="chaos-delay"
              type="number"
              min={0}
              max={600_000}
              step={500}
              value={delayMs}
              onChange={(event) => setDelayMs(Math.max(0, Math.trunc(Number(event.target.value) || 0)))}
              className="tabular"
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="chaos-count">Repeat count</Label>
            <Input
              id="chaos-count"
              type="number"
              min={1}
              max={100}
              step={1}
              value={count}
              onChange={(event) => setCount(Math.max(1, Math.trunc(Number(event.target.value) || 1)))}
              className="tabular"
            />
          </div>
        </div>

        {runId ? (
          <p className="mt-3 text-2xs text-muted-foreground">
            Injections are attributed to run <span className="mono-id">{runId}</span>.
          </p>
        ) : null}
      </div>

      {CHAOS_FAMILIES.map((family) => (
        <div key={family} className="space-y-2">
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wide text-foreground">
              {CHAOS_FAMILY_LABEL[family]}
            </h3>
            <p className="text-2xs text-muted-foreground">{CHAOS_FAMILY_BLURB[family]}</p>
          </div>

          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {chaosOfFamily(family).map((spec) => {
              const blocker = readiness(spec);
              return (
                <article
                  key={spec.type}
                  className={cn('surface-card flex flex-col gap-2 p-3', blocker && 'opacity-70')}
                  aria-label={spec.label}
                >
                  <div className="flex items-start justify-between gap-2">
                    <h4 className="text-sm font-medium text-foreground">{spec.label}</h4>
                    <Badge variant="subtle" className="mono-id shrink-0 text-2xs">
                      {spec.target}
                    </Badge>
                  </div>
                  <p className="text-xs text-muted-foreground">{spec.effect}</p>
                  <p className="text-2xs" style={{ color: 'var(--status-good)' }}>
                    Proves: {spec.proves}
                  </p>
                  <div className="mt-auto flex items-center gap-2 pt-1">
                    <Button
                      size="sm"
                      variant="outline"
                      className="w-full"
                      disabled={blocker !== null || injectMutation.isPending}
                      onClick={() => setPending(spec)}
                      title={blocker ?? undefined}
                    >
                      <Zap className="size-3.5" />
                      Inject
                    </Button>
                  </div>
                  {blocker ? (
                    <p className="text-2xs" style={{ color: 'var(--status-warning)' }}>
                      {blocker}
                    </p>
                  ) : null}
                </article>
              );
            })}
          </div>
        </div>
      ))}

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) setPending(null);
        }}
        title={pending ? `Inject ${pending.label}` : 'Inject chaos'}
        description={
          pending ? (
            <>
              {pending.effect} This changes real platform state and is recorded in{' '}
              <span className="mono-id">pdei.chaos_injections</span>.
            </>
          ) : null
        }
        destructive
        confirmLabel="Inject"
        onConfirm={async () => {
          if (!pending) return;
          await injectMutation.mutateAsync(pending);
          setPending(null);
        }}
      >
        {pending ? (
          <>
            <p className="text-xs text-muted-foreground">Request body:</p>
            <JsonViewer
              value={buildRequest(pending)}
              defaultExpandedDepth={3}
              maxHeight="12rem"
              copyable={false}
            />
          </>
        ) : null}
      </ConfirmDialog>

      <p className="text-2xs text-muted-foreground">
        Every type in <span className="mono-id">ChaosType</span> is represented:{' '}
        {Object.values(CHAOS_CATALOG).length} controls for{' '}
        {Object.values(CHAOS_CATALOG).length} enum members.
      </p>
    </section>
  );
}
