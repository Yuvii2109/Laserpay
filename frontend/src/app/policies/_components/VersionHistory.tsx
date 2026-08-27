'use client';

import { History, Info } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { DataTable, type DataTableColumn } from '@/components/shared/DataTable';
import { CopyableId } from '@/components/shared/CopyableId';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { shortenHash } from '@/lib/format/id';
import type { PolicyView } from '@/lib/types/policy';
import { policyLabel } from './policyDraft';

export interface PublishedVersion {
  policyId: string;
  policyVersionId: string;
  version: number;
  reasonCode: string;
  publishedAt: string;
  changes: string[];
}

export interface VersionHistoryProps {
  policies: readonly PolicyView[];
  /** Versions published from this browser session, newest first. */
  sessionVersions: readonly PublishedVersion[];
  isLoading: boolean;
  error: unknown;
  onRetry: () => void;
}

/**
 * Policy version history.
 *
 * Contract 8.1 exposes the *effective* version of each policy but no
 * `GET /policies/{id}/versions` route, so the full immutable chain cannot be listed from the
 * browser. What is shown is honest about that: the effective interval of every policy this
 * merchant owns, plus the versions this session published, which are the only earlier ones the
 * console has actually seen.
 */
export function VersionHistory({
  policies,
  sessionVersions,
  isLoading,
  error,
  onRetry,
}: VersionHistoryProps) {
  const columns: DataTableColumn<PolicyView>[] = [
    {
      id: 'reasonCode',
      header: 'Reason code',
      cell: (row) => (
        <span className="flex items-center gap-2">
          <span className="text-foreground">{policyLabel(row)}</span>
          {row.defaultPolicy ? (
            <Badge variant="subtle" className="text-2xs">
              baseline
            </Badge>
          ) : null}
        </span>
      ),
      sortValue: (row) => row.reasonCode ?? '',
    },
    {
      id: 'policyId',
      header: 'Policy',
      cell: (row) => <CopyableId id={row.policyId} link={false} />,
    },
    {
      id: 'version',
      header: 'Version',
      align: 'right',
      width: '5rem',
      cell: (row) => <span className="tabular">v{row.version}</span>,
      sortValue: (row) => row.version,
    },
    {
      id: 'policyVersionId',
      header: 'Version id',
      hideBelowSm: true,
      cell: (row) => <span className="mono-id text-2xs">{row.policyVersionId}</span>,
    },
    {
      id: 'effectiveFrom',
      header: 'Effective from',
      cell: (row) => <TimestampDisplay value={row.effectiveFrom} className="text-xs" />,
      sortValue: (row) => row.effectiveFrom,
    },
    {
      id: 'effectiveTo',
      header: 'Effective to',
      hideBelowSm: true,
      cell: (row) =>
        row.effectiveTo ? (
          <TimestampDisplay value={row.effectiveTo} className="text-xs" />
        ) : (
          <span className="text-2xs text-muted-foreground">in force</span>
        ),
      sortValue: (row) => row.effectiveTo ?? '9999',
    },
    {
      id: 'requirements',
      header: 'Rules',
      align: 'right',
      hideBelowSm: true,
      cell: (row) => <span className="tabular text-xs">{row.requirements.length}</span>,
      sortValue: (row) => row.requirements.length,
    },
    {
      id: 'createdBy',
      header: 'Created by',
      hideBelowSm: true,
      cell: (row) => <span className="text-xs text-muted-foreground">{row.createdBy}</span>,
      sortValue: (row) => row.createdBy,
    },
    {
      id: 'checksum',
      header: 'Checksum',
      hideBelowSm: true,
      cell: (row) => (
        <span className="mono-id text-2xs text-muted-foreground" title={row.checksum}>
          {shortenHash(row.checksum)}
        </span>
      ),
    },
  ];

  return (
    <section className="space-y-3" aria-label="Policy version history">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <History className="size-4 text-muted-foreground" aria-hidden />
          Version history
        </h2>
        <span className="text-2xs text-muted-foreground">
          {policies.length} policy record{policies.length === 1 ? '' : 's'} in force
        </span>
      </div>

      <DataTable
        columns={columns}
        rows={policies as PolicyView[]}
        getRowId={(row) => row.policyId}
        isLoading={isLoading}
        error={error}
        onRetry={onRetry}
        initialSort={{ columnId: 'effectiveFrom', direction: 'desc' }}
        emptyTitle="This merchant has no policies"
        emptyDescription="Policies are seeded from DefaultPolicyMatrix when a merchant is onboarded. If this is empty, platform-persistence has not run its V4 migration or the merchant was created outside the normal path."
      />

      {sessionVersions.length > 0 ? (
        <div className="surface-card p-4">
          <h3 className="text-sm font-semibold text-foreground">Published in this session</h3>
          <ul className="mt-2 space-y-2">
            {sessionVersions.map((version) => (
              <li key={`${version.policyVersionId}-${version.publishedAt}`} className="text-xs">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="primary" className="mono-id text-2xs">
                    {version.policyVersionId}
                  </Badge>
                  <span className="text-foreground">
                    {version.reasonCode} → v{version.version}
                  </span>
                  <TimestampDisplay value={version.publishedAt} className="text-2xs" />
                </div>
                {version.changes.length > 0 ? (
                  <ul className="mono-id mt-1 space-y-0.5 pl-3 text-2xs text-muted-foreground">
                    {version.changes.map((change, index) => (
                      <li key={index}>{change}</li>
                    ))}
                  </ul>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <Alert variant="info">
        <Info aria-hidden />
        <AlertTitle>Older versions are not listable from the console</AlertTitle>
        <AlertDescription>
          Every version is retained immutably in <span className="mono-id">pdei.policy_versions</span>,
          but contract 8.1 exposes no route to enumerate them. Until a{' '}
          <span className="mono-id">GET /policies/{'{policyId}'}/versions</span> route exists, the
          full chain is only visible in the audit log and in the database.
        </AlertDescription>
      </Alert>
    </section>
  );
}
