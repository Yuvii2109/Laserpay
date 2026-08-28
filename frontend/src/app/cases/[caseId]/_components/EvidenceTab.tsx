'use client';

import * as React from 'react';
import { Ban, CircleCheck, CircleSlash, ShieldAlert } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { DataTable, type DataTableColumn } from '@/components/shared/DataTable';
import { CopyableId } from '@/components/shared/CopyableId';
import { EvidenceTypeIcon, EVIDENCE_TYPE_LABEL } from '@/components/shared/EvidenceTypeIcon';
import { HashDisplay } from '@/components/shared/HashDisplay';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { humanizeEnum } from '@/lib/format/id';
import { isUsableEvidence } from '@/lib/types/evidence';
import type { EvidenceView } from '@/lib/types/evidence';
import type { CaseXRay } from '@/lib/types/case';
import type { RequirementStrength, RequirementView } from '@/lib/types/readiness';

/** How a single artifact relates to the requirement set of this case's reason code. */
type RequirementLink =
  | { kind: 'satisfies'; strength: RequirementStrength; requirement: RequirementView }
  | { kind: 'matches-unsatisfied'; strength: RequirementStrength; requirement: RequirementView }
  | { kind: 'prohibited'; strength: 'PROHIBITED'; requirement: RequirementView }
  | { kind: 'not-required'; strength: null; requirement: null };

function linkFor(evidence: EvidenceView, requirements: readonly RequirementView[]): RequirementLink {
  const satisfying = requirements.find((requirement) =>
    requirement.satisfyingEvidenceIds.includes(evidence.evidenceId),
  );
  if (satisfying) {
    return satisfying.strength === 'PROHIBITED'
      ? { kind: 'prohibited', strength: 'PROHIBITED', requirement: satisfying }
      : { kind: 'satisfies', strength: satisfying.strength, requirement: satisfying };
  }
  const byType = requirements.find((requirement) => requirement.type === evidence.type);
  if (!byType) return { kind: 'not-required', strength: null, requirement: null };
  if (byType.strength === 'PROHIBITED') {
    return { kind: 'prohibited', strength: 'PROHIBITED', requirement: byType };
  }
  return { kind: 'matches-unsatisfied', strength: byType.strength, requirement: byType };
}

const STRENGTH_TONE: Readonly<Record<RequirementStrength, string>> = {
  MANDATORY: 'var(--status-critical)',
  RECOMMENDED: 'var(--status-warning)',
  OPTIONAL: 'var(--status-neutral)',
  PROHIBITED: 'var(--status-critical)',
};

export interface EvidenceTabProps {
  xray: CaseXRay;
}

/**
 * Every artifact attached to the case, judged against the requirement set the readiness engine
 * used. The question this tab answers is not "what do we have" but "does what we have satisfy
 * the requirement it was supposed to satisfy" - which is why the requirement column comes
 * before the file.
 */
export function EvidenceTab({ xray }: EvidenceTabProps) {
  const requirements = React.useMemo(
    () => xray.readiness?.requirements ?? [],
    [xray.readiness],
  );

  const rows = React.useMemo(
    () =>
      [...xray.evidence].sort((a, b) => {
        const rank = (item: EvidenceView) => {
          const link = linkFor(item, requirements);
          if (link.kind === 'prohibited') return 0;
          if (link.strength === 'MANDATORY') return 1;
          if (link.strength === 'RECOMMENDED') return 2;
          if (link.strength === 'OPTIONAL') return 3;
          return 4;
        };
        return rank(a) - rank(b) || a.type.localeCompare(b.type);
      }),
    [xray.evidence, requirements],
  );

  const unsatisfied = requirements.filter(
    (requirement) => !requirement.satisfied && requirement.strength !== 'PROHIBITED',
  );
  const packageIds = new Set(
    (xray.packageManifest?.items ?? []).map((item) => item.evidenceId),
  );

  const columns: DataTableColumn<EvidenceView>[] = [
    {
      id: 'requirement',
      header: 'Requirement',
      width: '15rem',
      cell: (row) => {
        const link = linkFor(row, requirements);
        if (link.kind === 'not-required') {
          return (
            <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
              <CircleSlash className="size-3.5" aria-hidden />
              Not required here
            </span>
          );
        }
        if (link.kind === 'prohibited') {
          return (
            <span
              className="inline-flex items-center gap-1.5 text-xs font-medium"
              style={{ color: STRENGTH_TONE.PROHIBITED }}
            >
              <Ban className="size-3.5" aria-hidden />
              PROHIBITED
            </span>
          );
        }
        const satisfied = link.kind === 'satisfies';
        return (
          <span className="flex flex-col gap-0.5">
            <span
              className="inline-flex items-center gap-1.5 text-xs font-medium"
              style={{ color: STRENGTH_TONE[link.strength] }}
            >
              {satisfied ? (
                <CircleCheck className="size-3.5" aria-hidden />
              ) : (
                <ShieldAlert className="size-3.5" aria-hidden />
              )}
              {link.strength}
            </span>
            <span className="text-2xs text-muted-foreground">
              {satisfied ? 'satisfied by this artifact' : 'does not satisfy the requirement'}
            </span>
          </span>
        );
      },
      sortValue: (row) => linkFor(row, requirements).strength ?? 'ZZZ',
    },
    {
      id: 'type',
      header: 'Type',
      cell: (row) => <EvidenceTypeIcon type={row.type} withLabel />,
      sortValue: (row) => EVIDENCE_TYPE_LABEL[row.type],
    },
    {
      id: 'status',
      header: 'Status',
      cell: (row) => (
        <span className="flex items-center gap-1.5">
          <StatusBadge kind="evidence" value={row.status} />
          {!isUsableEvidence(row.status) ? (
            <span className="text-2xs text-muted-foreground">unusable</span>
          ) : null}
        </span>
      ),
      sortValue: (row) => row.status,
    },
    {
      id: 'version',
      header: 'Ver.',
      align: 'right',
      width: '4rem',
      cell: (row) => <span className="tabular text-xs">v{row.version}</span>,
      sortValue: (row) => row.version,
    },
    {
      id: 'sha256',
      header: 'sha256',
      hideBelowSm: true,
      cell: (row) => <HashDisplay sha256={row.sha256} className="text-2xs" />,
    },
    {
      id: 'provenance',
      header: 'Provenance',
      hideBelowSm: true,
      cell: (row) =>
        row.provenanceVerified ? (
          <span className="text-2xs" style={{ color: 'var(--status-good)' }}>
            verified
          </span>
        ) : (
          <span className="text-2xs" style={{ color: 'var(--status-serious)' }}>
            unverified
          </span>
        ),
      sortValue: (row) => row.provenanceVerified,
    },
    {
      id: 'package',
      header: 'In package',
      align: 'center',
      hideBelowSm: true,
      cell: (row) =>
        packageIds.has(row.evidenceId) ? (
          <Badge variant="primary">included</Badge>
        ) : (
          <span className="text-2xs text-muted-foreground">-</span>
        ),
      sortValue: (row) => packageIds.has(row.evidenceId),
    },
    {
      id: 'captured',
      header: 'Captured',
      hideBelowSm: true,
      cell: (row) => <TimestampDisplay value={row.createdAt} className="text-xs" />,
      sortValue: (row) => row.createdAt,
    },
    {
      id: 'evidenceId',
      header: 'Evidence',
      cell: (row) => <CopyableId id={row.evidenceId} shorten />,
    },
  ];

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <SummaryCard
          label="Artifacts attached"
          value={xray.evidence.length}
          hint={`${xray.evidence.filter((item) => isUsableEvidence(item.status)).length} usable (ACTIVE or EXPIRING).`}
        />
        <SummaryCard
          label="Mandatory satisfied"
          value={`${requirements.filter((r) => r.strength === 'MANDATORY' && r.satisfied).length}/${requirements.filter((r) => r.strength === 'MANDATORY').length}`}
          hint="Every mandatory requirement must be satisfied before a representment is defensible."
          tone={
            requirements.some((r) => r.strength === 'MANDATORY' && !r.satisfied)
              ? 'var(--status-critical)'
              : 'var(--status-good)'
          }
        />
        <SummaryCard
          label="Recommended satisfied"
          value={`${requirements.filter((r) => r.strength === 'RECOMMENDED' && r.satisfied).length}/${requirements.filter((r) => r.strength === 'RECOMMENDED').length}`}
          hint="Recommended requirements carry half weight in the contract 7 formula."
        />
      </div>

      {unsatisfied.length > 0 ? (
        <section
          className="rounded-lg border border-[color:var(--status-warning)]/40 bg-[color:var(--status-warning)]/8 p-4"
          aria-label="Unsatisfied requirements"
        >
          <h3 className="text-sm font-semibold text-foreground">
            {unsatisfied.length} requirement{unsatisfied.length === 1 ? '' : 's'} not satisfied
          </h3>
          <p className="mt-1 text-xs text-muted-foreground">
            These are the requirements of the applicable policy for{' '}
            <span className="mono-id">{xray.reasonCode}</span> that no usable artifact covers.
          </p>
          <ul className="mt-2 flex flex-wrap gap-2">
            {unsatisfied.map((requirement) => (
              <li
                key={requirement.type}
                className="inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs"
                style={{
                  color: STRENGTH_TONE[requirement.strength],
                  borderColor: `color-mix(in oklab, ${STRENGTH_TONE[requirement.strength]} 35%, transparent)`,
                }}
              >
                <EvidenceTypeIcon type={requirement.type} />
                {EVIDENCE_TYPE_LABEL[requirement.type]}
                <span className="opacity-70">· {requirement.strength}</span>
                <span className="tabular opacity-70">w{requirement.weight}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <DataTable
        columns={columns}
        rows={rows}
        getRowId={(row) => row.evidenceId}
        rowHref={(row) => `/evidence/${row.evidenceId}`}
        emptyTitle="No evidence attached to this case"
        emptyDescription="gatherEvidence links artifacts already known for the transaction. If this is empty the case is on the ACCEPT_LIABILITY short-circuit path."
        caption={`${rows.length} artifact${rows.length === 1 ? '' : 's'} · requirement strength comes from the readiness snapshot, not from the file itself`}
      />

      {requirements.some((requirement) => requirement.strength === 'PROHIBITED') ? (
        <p
          className="inline-flex items-center gap-1.5 text-2xs"
          style={{ color: 'var(--status-critical)' }}
        >
          <Ban className="size-3" aria-hidden />
          This policy prohibits{' '}
          {requirements
            .filter((requirement) => requirement.strength === 'PROHIBITED')
            .map((requirement) => humanizeEnum(requirement.type))
            .join(', ')}
          . A prohibited type in supportingEvidence is an automatic DENY.
        </p>
      ) : null}
    </div>
  );
}

function SummaryCard({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: React.ReactNode;
  hint: string;
  tone?: string;
}) {
  return (
    <div className={cn('surface-card p-3')}>
      <p className="text-2xs uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-1 text-xl font-semibold" style={tone ? { color: tone } : undefined}>
        {value}
      </p>
      <p className="mt-1 text-2xs text-muted-foreground">{hint}</p>
    </div>
  );
}
