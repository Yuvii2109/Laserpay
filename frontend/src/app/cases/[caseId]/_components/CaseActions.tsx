'use client';

import * as React from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, Send, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from '@/components/ui/sonner';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import { MoneyDisplay } from '@/components/shared/MoneyDisplay';
import { casesApi } from '@/lib/api/endpoints';
import { newCorrelationId } from '@/lib/api/client';
import { queryKeys } from '@/lib/query/keys';
import type { Money } from '@/lib/types/common';
import type { CaseCommandResult, CaseStatus, CaseView, CaseXRay } from '@/lib/types/case';

/**
 * Actor recorded on every human decision. There is no auth in this deployment posture
 * (frontend/context.md, known gap 8); when a session arrives, take the actor from it here.
 */
const CONSOLE_ACTOR = 'console-operator';

type Command = 'approve' | 'reject' | 'submit';

/** Status each command is expected to move the case to - corrected by the server response. */
const OPTIMISTIC_STATUS: Readonly<Record<Command, CaseStatus>> = {
  approve: 'PREPARED',
  reject: 'AWAITING_EVIDENCE',
  submit: 'SUBMITTED',
};

const SIGNAL_NAME: Readonly<Record<Command, string>> = {
  approve: 'humanDecision(approve)',
  reject: 'humanDecision(reject)',
  submit: 'submitRepresentment',
};

export interface CaseActionsProps {
  caseId: string;
  merchantId: string;
  status: CaseStatus;
  disputeAmount: Money;
  packageVersion: number | null;
  hasPackage: boolean;
}

/**
 * Approve / Reject / Submit.
 *
 * Each is a Temporal signal (contract 10), so each is gated by a confirmation that names the
 * signal and its consequence, and submission - the only action that leaves the platform -
 * requires the case id to be typed. Mutations never retry automatically (QueryProvider), and
 * every one invalidates the same key set a CASE_UPDATED frame would, so the screen is correct
 * even if the frame never arrives.
 */
export function CaseActions({
  caseId,
  merchantId,
  status,
  disputeAmount,
  packageVersion,
  hasPackage,
}: CaseActionsProps) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState<Command | null>(null);
  const [note, setNote] = React.useState('');

  const canDecide = status === 'AWAITING_APPROVAL';
  const canSubmit = status === 'PREPARED' && hasPackage;

  const invalidateAll = React.useCallback(() => {
    const keys = [
      queryKeys.cases.detail(caseId),
      queryKeys.cases.xray(caseId),
      queryKeys.cases.packageManifest(caseId),
      queryKeys.cases.all(),
      queryKeys.disputes.all(),
      queryKeys.investigations.all(),
      queryKeys.metrics.all(),
      queryKeys.audit.all(),
      queryKeys.merchants.summary(merchantId),
    ];
    for (const queryKey of keys) void queryClient.invalidateQueries({ queryKey });
  }, [caseId, merchantId, queryClient]);

  const mutation = useMutation<
    CaseCommandResult,
    Error,
    Command,
    { previousCase?: CaseView; previousXray?: CaseXRay }
  >({
    mutationFn: (command) => {
      const idempotencyKey = newCorrelationId();
      if (command === 'submit') {
        return casesApi.submit(
          caseId,
          {
            actor: CONSOLE_ACTOR,
            ...(packageVersion !== null ? { packageVersion } : {}),
          },
          idempotencyKey,
        );
      }
      const request = { actor: CONSOLE_ACTOR, ...(note.trim() ? { note: note.trim() } : {}) };
      return command === 'approve'
        ? casesApi.approve(caseId, request, idempotencyKey)
        : casesApi.reject(caseId, request, idempotencyKey);
    },

    // Optimistic: the operator sees the lane move immediately. The command result then
    // replaces the guess with the workflow's real answer.
    onMutate: async (command) => {
      await queryClient.cancelQueries({ queryKey: queryKeys.cases.detail(caseId) });
      await queryClient.cancelQueries({ queryKey: queryKeys.cases.xray(caseId) });

      const previousCase = queryClient.getQueryData<CaseView>(queryKeys.cases.detail(caseId));
      const previousXray = queryClient.getQueryData<CaseXRay>(queryKeys.cases.xray(caseId));
      const optimistic = OPTIMISTIC_STATUS[command];

      if (previousCase) {
        queryClient.setQueryData<CaseView>(queryKeys.cases.detail(caseId), {
          ...previousCase,
          status: optimistic,
          updatedAt: new Date().toISOString(),
        });
      }
      if (previousXray) {
        queryClient.setQueryData<CaseXRay>(queryKeys.cases.xray(caseId), {
          ...previousXray,
          caseStatus: optimistic,
        });
      }

      return {
        ...(previousCase ? { previousCase } : {}),
        ...(previousXray ? { previousXray } : {}),
      };
    },

    onError: (error, command, context) => {
      if (context?.previousCase) {
        queryClient.setQueryData(queryKeys.cases.detail(caseId), context.previousCase);
      }
      if (context?.previousXray) {
        queryClient.setQueryData(queryKeys.cases.xray(caseId), context.previousXray);
      }
      toast.error(`${command} failed`, { description: error.message });
    },

    onSuccess: (result, command) => {
      const current = queryClient.getQueryData<CaseView>(queryKeys.cases.detail(caseId));
      if (current) {
        queryClient.setQueryData<CaseView>(queryKeys.cases.detail(caseId), {
          ...current,
          status: result.status,
        });
      }
      const xray = queryClient.getQueryData<CaseXRay>(queryKeys.cases.xray(caseId));
      if (xray) {
        queryClient.setQueryData<CaseXRay>(queryKeys.cases.xray(caseId), {
          ...xray,
          caseStatus: result.status,
        });
      }
      setNote('');
      toast.success(`Case ${command}d`, {
        description: `Signal ${result.signal} accepted; case is now ${result.status}.`,
      });
    },

    onSettled: invalidateAll,
  });

  const run = (command: Command) => mutation.mutateAsync(command).then(() => undefined);

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={!canDecide || mutation.isPending}
          onClick={() => setOpen('reject')}
          title={canDecide ? undefined : 'Only a case parked on humanDecision can be rejected.'}
        >
          <X className="size-3.5" />
          Reject
        </Button>
        <Button
          size="sm"
          disabled={!canDecide || mutation.isPending}
          onClick={() => setOpen('approve')}
          title={canDecide ? undefined : 'Only a case parked on humanDecision can be approved.'}
        >
          <Check className="size-3.5" />
          Approve
        </Button>
        <Button
          size="sm"
          variant="destructive"
          disabled={!canSubmit || mutation.isPending}
          onClick={() => setOpen('submit')}
          title={
            canSubmit
              ? undefined
              : hasPackage
                ? 'A case can only be submitted from PREPARED.'
                : 'No representment package has been assembled yet.'
          }
        >
          <Send className="size-3.5" />
          Submit
        </Button>
      </div>

      <ConfirmDialog
        open={open === 'approve'}
        onOpenChange={(next) => setOpen(next ? 'approve' : null)}
        title="Approve this case"
        description={
          <>
            Signals <span className="mono-id">{SIGNAL_NAME.approve}</span> to workflow{' '}
            <span className="mono-id">case-{caseId}</span>. The workflow proceeds to
            prepareRepresentmentPackage; nothing is filed with the network yet.
          </>
        }
        confirmLabel="Approve"
        onConfirm={() => run('approve')}
      >
        <NoteField note={note} onChange={setNote} />
      </ConfirmDialog>

      <ConfirmDialog
        open={open === 'reject'}
        onOpenChange={(next) => setOpen(next ? 'reject' : null)}
        title="Reject this case"
        description={
          <>
            Signals <span className="mono-id">{SIGNAL_NAME.reject}</span> to workflow{' '}
            <span className="mono-id">case-{caseId}</span>. The case returns to evidence
            gathering and the rejection is written to the audit chain.
          </>
        }
        confirmLabel="Reject"
        onConfirm={() => run('reject')}
      >
        <NoteField note={note} onChange={setNote} required />
      </ConfirmDialog>

      <ConfirmDialog
        open={open === 'submit'}
        onOpenChange={(next) => setOpen(next ? 'submit' : null)}
        title="Submit the representment"
        destructive
        requireTypedConfirmation={caseId}
        description={
          <>
            This is the only action that leaves the platform. Package v{packageVersion ?? '?'} is
            filed with the network for a disputed amount of{' '}
            <MoneyDisplay money={disputeAmount} withCode /> and cannot be recalled.
          </>
        }
        confirmLabel="Submit representment"
        onConfirm={() => run('submit')}
      />
    </>
  );
}

function NoteField({
  note,
  onChange,
  required = false,
}: {
  note: string;
  onChange: (value: string) => void;
  required?: boolean;
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor="decision-note">
        Note {required ? '' : <span className="text-muted-foreground">(optional)</span>}
      </Label>
      <Input
        id="decision-note"
        value={note}
        onChange={(event) => onChange(event.target.value)}
        placeholder="Why this decision - recorded on the audit event"
        autoComplete="off"
      />
    </div>
  );
}
