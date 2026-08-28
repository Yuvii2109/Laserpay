'use client';

import * as React from 'react';
import { Download, ExternalLink, FileArchive, Info } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { toast } from '@/components/ui/sonner';
import { DataTable, type DataTableColumn } from '@/components/shared/DataTable';
import { CopyableId } from '@/components/shared/CopyableId';
import { EmptyState } from '@/components/shared/EmptyState';
import { EvidenceTypeIcon, EVIDENCE_TYPE_LABEL } from '@/components/shared/EvidenceTypeIcon';
import { formatBytes } from '@/components/shared/EvidenceCard';
import { HashDisplay } from '@/components/shared/HashDisplay';
import { MoneyDisplay } from '@/components/shared/MoneyDisplay';
import { ReadinessBadge } from '@/components/shared/ReadinessBadge';
import { TimestampDisplay } from '@/components/shared/TimestampDisplay';
import { evidenceApi } from '@/lib/api/endpoints';
import { humanizeEnum } from '@/lib/format/id';
import type { PackageManifest, PackageManifestItem } from '@/lib/types/case';

export interface PackageTabProps {
  manifest: PackageManifest | null;
  caseId: string;
}

/**
 * The representment package manifest.
 *
 * A package is the only artifact this platform sends outside itself, so the manifest is shown
 * as a ledger: every file, its version, its sha256, and where it sits inside the bundle. The
 * manifest itself is downloadable from the browser; the zip lives in MinIO under
 * `pdei-packages` (contract 11) and has no gateway route yet - the object key is shown instead
 * of a broken button.
 */
export function PackageTab({ manifest, caseId }: PackageTabProps) {
  const [downloading, setDownloading] = React.useState(false);

  const downloadManifest = () => {
    if (!manifest) return;
    setDownloading(true);
    try {
      const blob = new Blob([JSON.stringify(manifest, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `manifest-${manifest.caseId}-v${manifest.packageVersion}.json`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      toast.success('Manifest downloaded');
    } catch {
      toast.error('The browser refused the download');
    } finally {
      setDownloading(false);
    }
  };

  if (!manifest) {
    return (
      <EmptyState
        icon={FileArchive}
        title="No representment package yet"
        description={`prepareRepresentmentPackage has not run for ${caseId}. A package is only assembled after the safety gate allows the case to proceed.`}
      />
    );
  }

  const columns: DataTableColumn<PackageManifestItem>[] = [
    {
      id: 'entryPath',
      header: 'Path in bundle',
      cell: (row) => <span className="mono-id text-xs">{row.entryPath}</span>,
      sortValue: (row) => row.entryPath,
    },
    {
      id: 'type',
      header: 'Type',
      cell: (row) => <EvidenceTypeIcon type={row.type} withLabel />,
      sortValue: (row) => EVIDENCE_TYPE_LABEL[row.type],
    },
    {
      id: 'strength',
      header: 'Requirement',
      cell: (row) => <Badge variant="outline">{row.strength}</Badge>,
      sortValue: (row) => row.strength,
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
      id: 'size',
      header: 'Size',
      align: 'right',
      hideBelowSm: true,
      cell: (row) => <span className="tabular text-xs">{formatBytes(row.sizeBytes)}</span>,
      sortValue: (row) => row.sizeBytes,
    },
    {
      id: 'captured',
      header: 'Captured',
      hideBelowSm: true,
      cell: (row) => <TimestampDisplay value={row.capturedAt} className="text-xs" />,
      sortValue: (row) => row.capturedAt,
    },
    {
      id: 'evidence',
      header: 'Evidence',
      cell: (row) => <CopyableId id={row.evidenceId} shorten />,
    },
    {
      id: 'download',
      header: '',
      align: 'right',
      width: '5rem',
      cell: (row) => (
        <Button
          variant="ghost"
          size="sm"
          asChild
          onClick={(event) => event.stopPropagation()}
        >
          <a
            href={evidenceApi.downloadUrl(row.evidenceId)}
            target="_blank"
            rel="noreferrer"
            aria-label={`Download ${row.filename}`}
          >
            <ExternalLink className="size-3.5" />
            Open
          </a>
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <section className="surface-card p-4" aria-label="Bundle">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <FileArchive className="size-4 text-muted-foreground" aria-hidden />
              Representment package v{manifest.packageVersion}
            </h2>
            <p className="mono-id mt-1 break-all text-xs text-muted-foreground">
              {manifest.bundleObjectKey}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" onClick={downloadManifest} disabled={downloading}>
              <Download className="size-3.5" />
              Download manifest.json
            </Button>
          </div>
        </div>

        <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 text-sm lg:grid-cols-4">
          <Field label="Files">
            <span className="tabular">{manifest.items.length}</span>
          </Field>
          <Field label="Bundle size">
            <span className="tabular">{formatBytes(manifest.bundleSizeBytes)}</span>
          </Field>
          <Field label="Disputed amount">
            <MoneyDisplay money={manifest.disputeAmount} />
          </Field>
          <Field label="Reason code">
            <Badge variant="outline">{humanizeEnum(manifest.reasonCode)}</Badge>
          </Field>
          <Field label="Readiness at assembly">
            <ReadinessBadge band={manifest.readinessBand} score={manifest.readinessScore} />
          </Field>
          <Field label="Policy version">
            <span className="mono-id text-xs">{manifest.policyVersionId ?? 'unknown'}</span>
          </Field>
          <Field label="Generated by">
            <span className="mono-id text-xs">{manifest.generatedBy}</span>
          </Field>
          <Field label="Generated at">
            <TimestampDisplay value={manifest.generatedAt} mode="absolute" className="text-xs" />
          </Field>
        </dl>

        <div className="mt-4 rounded-md border border-border p-3">
          <p className="text-2xs uppercase tracking-wide text-muted-foreground">Bundle sha256</p>
          <HashDisplay
            className="mt-1 break-all text-xs"
            sha256={manifest.bundleSha256}
            full
            withIcon
            label="Bundle sha256"
          />
          <p className="mt-1 text-2xs text-muted-foreground">
            Compare this against{' '}
            <span className="mono-id">x-amz-meta-sha256</span> on the MinIO object before filing.
          </p>
        </div>
      </section>

      {manifest.narrative ? (
        <section className="surface-card p-4" aria-label="Package narrative">
          <h2 className="text-sm font-semibold text-foreground">Narrative filed with the package</h2>
          <p className="mt-2 whitespace-pre-line text-sm leading-relaxed text-foreground">
            {manifest.narrative}
          </p>
        </section>
      ) : null}

      <DataTable
        columns={columns}
        rows={manifest.items}
        getRowId={(row) => `${row.evidenceId}-v${row.version}`}
        emptyTitle="The manifest lists no files"
        emptyDescription="A package with no evidence cannot be argued. Check that gatherEvidence linked artifacts before the bundle was assembled."
        caption={`${manifest.items.length} file(s) · hashes are the ones recorded at capture time, not recomputed by the console`}
      />

      <Alert variant="info">
        <Info aria-hidden />
        <AlertTitle>The zip itself is not downloadable from the console</AlertTitle>
        <AlertDescription>
          Contract 8.1 exposes <span className="mono-id">GET /cases/{'{caseId}'}/package</span> for
          the manifest but no route for the bundle. Individual files are reachable through{' '}
          <span className="mono-id">GET /evidence/{'{id}'}/download</span> (the Open buttons
          above); the bundle lives at <span className="mono-id">{manifest.bundleObjectKey}</span>{' '}
          in the <span className="mono-id">pdei-packages</span> bucket.
        </AlertDescription>
      </Alert>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-2xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 truncate">{children}</dd>
    </div>
  );
}
