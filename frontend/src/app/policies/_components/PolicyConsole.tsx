'use client';

import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { RotateCcw, ScrollText, Upload } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { toast } from '@/components/ui/sonner';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { LoadingState } from '@/components/shared/LoadingState';
import { PageHeader } from '@/components/shared/PageHeader';
import { policiesApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { useSelectedMerchantId } from '@/lib/store/uiStore';
import type { EvidenceType } from '@/lib/types/evidence';
import type { PolicyDraft } from '@/lib/types/policy';
import { RequirementMatrix } from './RequirementMatrix';
import { ThresholdsPanel } from './ThresholdsPanel';
import { VersionHistory, type PublishedVersion } from './VersionHistory';
import {
  describeChanges,
  isDirty,
  policyLabel,
  setCell,
  toDraft,
  type CellValue,
} from './policyDraft';

interface DraftEntry {
  /** The version the draft was seeded from; a republish invalidates the draft. */
  versionId: string;
  draft: PolicyDraft;
}

/**
 * The policy console.
 *
 * Three surfaces over one immutable resource: the requirement matrix (what evidence each
 * reason code needs), the automation thresholds (when the platform may act without a person),
 * and the version history (what was in force when). Saving publishes new versions - it never
 * edits one, because a case gated last week must stay explainable by the policy that gated it.
 */
export function PolicyConsole({ initialPolicyId }: { initialPolicyId: string | null }) {
  const merchantId = useSelectedMerchantId();
  const queryClient = useQueryClient();

  const policiesQuery = useQuery({
    queryKey: queryKeys.policies.list({ merchantId: merchantId ?? undefined }),
    // `GET /policies` requires merchantId and returns a bare array, not a page envelope.
    queryFn: ({ signal }) => policiesApi.list({ merchantId: merchantId as string }, signal),
    enabled: Boolean(merchantId),
  });

  const policies = React.useMemo(() => {
    const items = policiesQuery.data ?? [];
    // Baseline last: it is the fallback profile, not a reason code.
    return [...items].sort((a, b) => {
      if (a.defaultPolicy !== b.defaultPolicy) return a.defaultPolicy ? 1 : -1;
      return (a.reasonCode ?? '').localeCompare(b.reasonCode ?? '');
    });
  }, [policiesQuery.data]);

  const [entries, setEntries] = React.useState<Record<string, DraftEntry>>({});
  const [selectedPolicyId, setSelectedPolicyId] = React.useState<string | null>(initialPolicyId);
  const [sessionVersions, setSessionVersions] = React.useState<PublishedVersion[]>([]);
  const [publishOpen, setPublishOpen] = React.useState(false);

  // Seed a draft per policy, and re-seed whenever the server hands back a newer version.
  React.useEffect(() => {
    if (policies.length === 0) return;
    setEntries((current) => {
      let changed = false;
      const next = { ...current };
      for (const policy of policies) {
        const existing = next[policy.policyId];
        if (!existing || existing.versionId !== policy.policyVersionId) {
          next[policy.policyId] = {
            versionId: policy.policyVersionId,
            draft: toDraft(policy),
          };
          changed = true;
        }
      }
      return changed ? next : current;
    });
  }, [policies]);

  React.useEffect(() => {
    if (selectedPolicyId && policies.some((policy) => policy.policyId === selectedPolicyId)) return;
    setSelectedPolicyId(policies[0]?.policyId ?? null);
  }, [policies, selectedPolicyId]);

  const drafts = React.useMemo(() => {
    const map: Record<string, PolicyDraft> = {};
    for (const [policyId, entry] of Object.entries(entries)) map[policyId] = entry.draft;
    return map;
  }, [entries]);

  const dirtyPolicies = React.useMemo(
    () =>
      policies.filter((policy) => {
        const draft = drafts[policy.policyId];
        return draft ? isDirty(policy, draft) : false;
      }),
    [policies, drafts],
  );

  const dirtyIds = React.useMemo(
    () => new Set(dirtyPolicies.map((policy) => policy.policyId)),
    [dirtyPolicies],
  );

  const selectedPolicy = policies.find((policy) => policy.policyId === selectedPolicyId) ?? null;
  const selectedDraft = selectedPolicyId ? drafts[selectedPolicyId] : undefined;

  const updateDraft = React.useCallback((policyId: string, next: PolicyDraft) => {
    setEntries((current) => {
      const existing = current[policyId];
      if (!existing) return current;
      return { ...current, [policyId]: { ...existing, draft: next } };
    });
  }, []);

  const onCellChange = React.useCallback(
    (policyId: string, type: EvidenceType, value: CellValue) => {
      setEntries((current) => {
        const existing = current[policyId];
        if (!existing) return current;
        return {
          ...current,
          [policyId]: { ...existing, draft: setCell(existing.draft, type, value) },
        };
      });
    },
    [],
  );

  const resetAll = () => {
    setEntries(() => {
      const next: Record<string, DraftEntry> = {};
      for (const policy of policies) {
        next[policy.policyId] = { versionId: policy.policyVersionId, draft: toDraft(policy) };
      }
      return next;
    });
    toast.info('Edits discarded');
  };

  const publishMutation = useMutation({
    mutationFn: async () => {
      const published: PublishedVersion[] = [];
      // Sequential on purpose: each PUT closes the previous interval, and a failure halfway
      // must leave a comprehensible state rather than a racing set of partial versions.
      for (const policy of dirtyPolicies) {
        const draft = drafts[policy.policyId];
        if (!draft) continue;
        const changes = describeChanges(policy, draft);
        const result = await policiesApi.update(policy.policyId, draft);
        published.push({
          policyId: result.policyId,
          policyVersionId: result.policyVersionId,
          version: result.version,
          reasonCode: policyLabel(result),
          publishedAt: new Date().toISOString(),
          changes,
        });
      }
      return published;
    },
    onSuccess: (published) => {
      setSessionVersions((current) => [...published.reverse(), ...current].slice(0, 25));
      void queryClient.invalidateQueries({ queryKey: queryKeys.policies.all() });
      // A new policy version changes readiness scoring inputs for every future computation.
      void queryClient.invalidateQueries({ queryKey: queryKeys.transactions.all() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.gaps.all() });
      toast.success(
        `${published.length} new policy version${published.length === 1 ? '' : 's'} published`,
      );
    },
    onError: (error: Error) => {
      toast.error('Publishing failed', { description: error.message });
    },
  });

  if (!merchantId) {
    return (
      <div className="space-y-5">
        <PolicyHeader dirtyCount={0} onReset={resetAll} onPublish={() => undefined} busy={false} />
        <EmptyState
          icon={ScrollText}
          title="Select a merchant"
          description="Policies are per merchant and per reason code. Pick a merchant in the top bar."
        />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <PolicyHeader
        dirtyCount={dirtyPolicies.length}
        onReset={resetAll}
        onPublish={() => setPublishOpen(true)}
        busy={publishMutation.isPending}
      />

      {policiesQuery.isError ? (
        <ErrorState error={policiesQuery.error} onRetry={() => void policiesQuery.refetch()} />
      ) : policiesQuery.isLoading ? (
        <LoadingState variant="panel" label="Loading policies" />
      ) : policies.length === 0 ? (
        <EmptyState
          icon={ScrollText}
          title="No policies for this merchant"
          description="PolicyEngine falls back to DefaultPolicyMatrix when a merchant has no policy of its own, so readiness still computes - but nothing here is editable until a policy row exists."
        />
      ) : (
        <>
          <section aria-label="Requirement matrix" className="space-y-2">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="text-sm font-semibold text-foreground">Requirement matrix</h2>
              <span className="text-2xs text-muted-foreground">
                Rows are the merchant&apos;s policies; a reason code with no row has no policy of
                its own and falls back to the platform default.
              </span>
            </div>
            <RequirementMatrix
              policies={policies}
              drafts={drafts}
              dirtyPolicyIds={dirtyIds}
              onCellChange={onCellChange}
              selectedPolicyId={selectedPolicyId}
              onSelectPolicy={setSelectedPolicyId}
            />
          </section>

          {selectedPolicy && selectedDraft ? (
            <ThresholdsPanel
              policy={selectedPolicy}
              draft={selectedDraft}
              onChange={(next) => updateDraft(selectedPolicy.policyId, next)}
            />
          ) : null}

          <VersionHistory
            policies={policies}
            sessionVersions={sessionVersions}
            isLoading={policiesQuery.isLoading}
            error={policiesQuery.error}
            onRetry={() => void policiesQuery.refetch()}
          />
        </>
      )}

      <ConfirmDialog
        open={publishOpen}
        onOpenChange={setPublishOpen}
        title={`Publish ${dirtyPolicies.length} new policy version${dirtyPolicies.length === 1 ? '' : 's'}`}
        description="Each edited policy gets a new immutable version. Existing versions are never modified, and cases already gated keep the version that gated them."
        confirmLabel="Publish new versions"
        onConfirm={async () => {
          await publishMutation.mutateAsync();
          setPublishOpen(false);
        }}
      >
        <div className="max-h-64 space-y-3 overflow-y-auto scrollbar-thin">
          {dirtyPolicies.map((policy) => {
            const draft = drafts[policy.policyId];
            const changes = draft ? describeChanges(policy, draft) : [];
            return (
              <div key={policy.policyId} className="rounded-md border border-border p-3">
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <Badge variant="outline">{policyLabel(policy)}</Badge>
                  <span className="mono-id text-2xs text-muted-foreground">
                    {policy.policyId} · v{policy.version} → v{policy.version + 1}
                  </span>
                </div>
                <ul className="mono-id mt-1.5 space-y-0.5 text-2xs text-muted-foreground">
                  {changes.length === 0 ? (
                    <li>No field-level differences detected.</li>
                  ) : (
                    changes.map((change, index) => <li key={index}>{change}</li>)
                  )}
                </ul>
              </div>
            );
          })}
        </div>
      </ConfirmDialog>
    </div>
  );
}

function PolicyHeader({
  dirtyCount,
  onReset,
  onPublish,
  busy,
}: {
  dirtyCount: number;
  onReset: () => void;
  onPublish: () => void;
  busy: boolean;
}) {
  return (
    <PageHeader
      eyebrow="Defend"
      title="Policies"
      description="What each dispute reason code requires as proof, and how confident the platform must be before it acts without a person. Policy versions are immutable: saving publishes a new one."
      meta={
        dirtyCount > 0 ? (
          <Badge variant="primary">
            {dirtyCount} unpublished edit{dirtyCount === 1 ? '' : 's'}
          </Badge>
        ) : (
          <Badge variant="subtle">No unpublished edits</Badge>
        )
      }
      actions={
        <>
          <Button variant="outline" size="sm" onClick={onReset} disabled={dirtyCount === 0 || busy}>
            <RotateCcw className="size-3.5" />
            Discard edits
          </Button>
          <Button size="sm" onClick={onPublish} disabled={dirtyCount === 0 || busy}>
            <Upload className={cn('size-3.5', busy && 'animate-pulse')} />
            Publish new version{dirtyCount === 1 ? '' : 's'}
          </Button>
        </>
      }
    />
  );
}
