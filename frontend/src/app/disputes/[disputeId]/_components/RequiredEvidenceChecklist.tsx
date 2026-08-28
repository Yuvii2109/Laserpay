'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Check, ClipboardList, Minus, X } from 'lucide-react';
import {
  EmptyState,
  ErrorState,
  EvidenceTypeIcon,
  EVIDENCE_TYPE_LABEL,
  LoadingState,
} from '@/components/shared';
import { Badge } from '@/components/ui/badge';
import { policiesApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import { humanizeEnum } from '@/lib/format/id';
import { formatRatio } from '@/lib/format/money';
import type { DisputeReasonCode } from '@/lib/types/dispute';
import type { RequirementSpec } from '@/lib/types/policy';
import type { ReadinessSnapshot, RequirementStrength } from '@/lib/types/readiness';

export interface RequiredEvidenceChecklistProps {
  reasonCode: DisputeReasonCode;
  merchantId: string;
  /** Readiness snapshot for the disputed transaction; supplies the satisfied flags. */
  snapshot: ReadinessSnapshot | undefined;
  snapshotLoading?: boolean;
}

const STRENGTH_ORDER: Readonly<Record<RequirementStrength, number>> = {
  MANDATORY: 0,
  RECOMMENDED: 1,
  OPTIONAL: 2,
  PROHIBITED: 3,
};

interface ChecklistRow {
  spec: RequirementSpec;
  satisfied: boolean | null;
  satisfyingEvidenceIds: string[];
}

/**
 * What this reason code demands, and whether the transaction has it.
 *
 * Two sources, joined on evidence type: `GET /requirements?reasonCode=` gives the *rules*
 * (strength, max age, provenance, quality floor) from the applicable policy version, and the
 * transaction's `ReadinessSnapshot` gives the *result* (satisfied, and by which artifact).
 * Keeping them separate matters - a requirement can be unsatisfied because the artifact is
 * missing, or because the artifact exists and is too old for this policy.
 */
export function RequiredEvidenceChecklist({
  reasonCode,
  merchantId,
  snapshot,
  snapshotLoading = false,
}: RequiredEvidenceChecklistProps) {
  const requirementsQuery = useQuery({
    queryKey: queryKeys.policies.requirementsForReason(reasonCode, merchantId),
    queryFn: ({ signal }) => policiesApi.requirementsForReason(reasonCode, merchantId, signal),
  });

  const rows = React.useMemo<ChecklistRow[]>(() => {
    // `GET /requirements` answers with the RequirementsResponse envelope, not a bare array.
    const specs = requirementsQuery.data?.requirements ?? [];
    const byType = new Map(snapshot?.requirements.map((item) => [item.type, item]) ?? []);
    return [...specs]
      .sort(
        (a, b) =>
          STRENGTH_ORDER[a.strength] - STRENGTH_ORDER[b.strength] || a.type.localeCompare(b.type),
      )
      .map((spec) => {
        const view = byType.get(spec.type);
        return {
          spec,
          satisfied: view ? view.satisfied : null,
          satisfyingEvidenceIds: view?.satisfyingEvidenceIds ?? [],
        };
      });
  }, [requirementsQuery.data, snapshot]);

  if (requirementsQuery.isLoading) return <LoadingState variant="rows" count={4} />;
  if (requirementsQuery.isError) {
    return (
      <ErrorState
        error={requirementsQuery.error}
        onRetry={() => void requirementsQuery.refetch()}
        compact
      />
    );
  }

  if (rows.length === 0) {
    return (
      <EmptyState
        icon={ClipboardList}
        title="No requirement matrix for this reason code"
        description="The applicable policy declares no evidence requirements for this reason code, so readiness falls back to the merchant's baseline profile."
        compact
      />
    );
  }

  const mandatory = rows.filter((row) => row.spec.strength === 'MANDATORY');
  const mandatorySatisfied = mandatory.filter((row) => row.satisfied === true).length;

  return (
    <div className="space-y-3">
      <p className="text-xs text-muted-foreground">
        {snapshot
          ? `${mandatorySatisfied} of ${mandatory.length} mandatory requirement${mandatory.length === 1 ? '' : 's'} satisfied.`
          : snapshotLoading
            ? 'Loading the transaction readiness snapshot…'
            : 'No readiness snapshot for the disputed transaction, so satisfaction is unknown. The rules below still apply.'}{' '}
        Contract 9.3 rule 7 rejects a DEFENDABLE classification while any mandatory requirement
        is unsatisfied.
      </p>

      <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border">
        {rows.map((row) => (
          <li key={row.spec.type} className="flex flex-wrap items-start gap-x-3 gap-y-2 p-3">
            <SatisfactionMark satisfied={row.satisfied} strength={row.spec.strength} />

            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                <span className="inline-flex items-center gap-2 text-sm text-foreground">
                  <EvidenceTypeIcon type={row.spec.type} />
                  {EVIDENCE_TYPE_LABEL[row.spec.type] ?? humanizeEnum(row.spec.type)}
                </span>
                <Badge
                  variant={row.spec.strength === 'MANDATORY' ? 'primary' : 'subtle'}
                  className="text-2xs"
                >
                  {humanizeEnum(row.spec.strength)}
                </Badge>
                <span className="tabular text-xs text-muted-foreground">
                  weight {row.spec.weight}
                </span>
              </div>

              <p className="mt-0.5 text-xs text-muted-foreground">
                {row.spec.maxAgeDays === null
                  ? 'No max age'
                  : `Must be no older than ${row.spec.maxAgeDays} day${row.spec.maxAgeDays === 1 ? '' : 's'}`}
                {' · '}
                {row.spec.provenanceRequired ? 'provenance required' : 'provenance optional'}
                {' · '}
                quality floor {formatRatio(row.spec.minQualityScore)}
                {row.spec.note ? ` · ${row.spec.note}` : ''}
              </p>

              {row.satisfyingEvidenceIds.length > 0 ? (
                <p className="mt-1 flex flex-wrap gap-x-2 gap-y-1">
                  {row.satisfyingEvidenceIds.map((evidenceId) => (
                    <Link
                      key={evidenceId}
                      href={`/evidence/${evidenceId}`}
                      className="mono-id text-xs underline-offset-4 hover:underline"
                    >
                      {evidenceId}
                    </Link>
                  ))}
                </p>
              ) : null}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

function SatisfactionMark({
  satisfied,
  strength,
}: {
  satisfied: boolean | null;
  strength: RequirementStrength;
}) {
  if (satisfied === null) {
    return (
      <span
        className="mt-0.5 inline-flex size-5 shrink-0 items-center justify-center rounded-full border border-dashed border-border"
        title="Satisfaction unknown - no readiness snapshot"
      >
        <Minus className="size-3 text-muted-foreground" aria-hidden />
        <span className="sr-only">Unknown</span>
      </span>
    );
  }
  if (satisfied) {
    return (
      <span
        className="mt-0.5 inline-flex size-5 shrink-0 items-center justify-center rounded-full"
        style={{ backgroundColor: 'color-mix(in oklab, var(--status-good) 18%, transparent)' }}
      >
        <Check className="size-3.5" style={{ color: 'var(--status-good)' }} aria-hidden />
        <span className="sr-only">Satisfied</span>
      </span>
    );
  }
  const critical = strength === 'MANDATORY';
  return (
    <span
      className="mt-0.5 inline-flex size-5 shrink-0 items-center justify-center rounded-full"
      style={{
        backgroundColor: `color-mix(in oklab, ${critical ? 'var(--status-critical)' : 'var(--status-neutral)'} 18%, transparent)`,
      }}
    >
      <X
        className="size-3.5"
        style={{ color: critical ? 'var(--status-critical)' : 'var(--status-neutral)' }}
        aria-hidden
      />
      <span className="sr-only">{critical ? 'Not satisfied (mandatory)' : 'Not satisfied'}</span>
    </span>
  );
}
