'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FileWarning, Loader2, ShieldCheck, ShieldX } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { toast } from '@/components/ui/sonner';
import { HashDisplay, TimestampDisplay } from '@/components/shared';
import { evidenceApi } from '@/lib/api/endpoints';
import { queryKeys } from '@/lib/query/keys';
import type { EvidenceView, IntegrityReport } from '@/lib/types/evidence';

export interface EvidenceIntegrityPanelProps {
  evidence: EvidenceView;
}

/**
 * The digest and the integrity check.
 *
 * `POST /evidence/{id}/verify` re-reads the object out of MinIO, re-hashes the bytes and
 * compares them with the digest recorded at capture time (contract 11 stores it as
 * `x-amz-meta-sha256`). It is the only operation on this page that costs anything, so it is
 * explicit rather than automatic — and it is a read: a failed check marks the artifact
 * upstream, this console never edits it.
 */
export function EvidenceIntegrityPanel({ evidence }: EvidenceIntegrityPanelProps) {
  const queryClient = useQueryClient();

  const verify = useMutation({
    mutationFn: () => evidenceApi.verify(evidence.evidenceId),
    onSuccess: (result) => {
      queryClient.setQueryData(queryKeys.evidence.integrity(evidence.evidenceId), result);
      // A failed check can flip the artifact's status upstream; re-read the artifact itself.
      void queryClient.invalidateQueries({
        queryKey: queryKeys.evidence.detail(evidence.evidenceId),
      });
      if (result.intact) toast.success('Integrity verified: stored bytes match the recorded digest');
      else toast.error('Integrity check failed — the stored object does not match its digest');
    },
    onError: (cause) => {
      toast.error(cause instanceof Error ? cause.message : 'Verification failed');
    },
  });

  /**
   * The report is a command result, never a fetch: the mutation holds the fresh one and the
   * cache holds whatever a previous mount produced, so remounting this panel does not silently
   * lose a check the operator already paid for.
   */
  const report =
    verify.data ??
    queryClient.getQueryData<IntegrityReport>(queryKeys.evidence.integrity(evidence.evidenceId));

  return (
    <Card className="space-y-4 p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold tracking-tight">Integrity</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            sha256 recorded when these bytes were stored.
          </p>
        </div>
        <Button onClick={() => verify.mutate()} disabled={verify.isPending} variant="outline" size="sm">
          {verify.isPending ? (
            <Loader2 className="size-4 animate-spin" aria-hidden />
          ) : (
            <ShieldCheck className="size-4" aria-hidden />
          )}
          Verify
        </Button>
      </div>

      <div className="space-y-1.5">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Recorded sha256
        </p>
        <HashDisplay
          sha256={evidence.sha256}
          full
          withIcon
          label={`sha256 of ${evidence.evidenceId}`}
        />
      </div>

      <div className="space-y-1.5">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Object key
        </p>
        <p className="mono-id break-all text-xs text-muted-foreground">{evidence.objectKey}</p>
      </div>

      {report ? <IntegrityResult report={report} /> : (
        <p className="text-xs text-muted-foreground">
          Not verified in this session. Verifying streams the stored object, re-hashes it and
          compares — it does not modify anything.
        </p>
      )}
    </Card>
  );
}

function IntegrityResult({ report }: { report: IntegrityReport }) {
  if (report.objectMissing) {
    return (
      <Alert variant="critical">
        <FileWarning className="size-4" style={{ color: 'var(--status-critical)' }} />
        <AlertTitle>Object missing from storage</AlertTitle>
        <AlertDescription className="space-y-1.5">
          <p>
            The evidence row exists but no object was found at{' '}
            <span className="mono-id">{report.objectKey}</span>. Checked{' '}
            <TimestampDisplay value={report.verifiedAt} />.
          </p>
          {report.detail ? <p>{report.detail}</p> : null}
        </AlertDescription>
      </Alert>
    );
  }

  if (!report.intact) {
    return (
      <Alert variant="critical">
        <ShieldX className="size-4" style={{ color: 'var(--status-critical)' }} />
        <AlertTitle>Digest mismatch — these bytes are not the bytes we recorded</AlertTitle>
        <AlertDescription className="space-y-2">
          <div className="space-y-1">
            <p className="text-xs uppercase tracking-wide">Expected</p>
            <HashDisplay sha256={report.expectedSha256} full label="expected sha256" />
          </div>
          <div className="space-y-1">
            <p className="text-xs uppercase tracking-wide">Recomputed</p>
            <HashDisplay sha256={report.actualSha256} full label="recomputed sha256" />
          </div>
          {report.detail ? <p>{report.detail}</p> : null}
          <p>
            Checked <TimestampDisplay value={report.verifiedAt} />. An artifact that fails this
            check cannot support a representment claim.
          </p>
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <Alert variant="good">
      <ShieldCheck className="size-4" style={{ color: 'var(--status-good)' }} />
      <AlertTitle>Intact</AlertTitle>
      <AlertDescription>
        The stored object re-hashes to the recorded digest. Checked{' '}
        <TimestampDisplay value={report.verifiedAt} />.
      </AlertDescription>
    </Alert>
  );
}
