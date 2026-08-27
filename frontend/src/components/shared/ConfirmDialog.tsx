'use client';

import * as React from 'react';
import { Loader2 } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

export interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: React.ReactNode;
  /** Extra content between description and footer, e.g. a note field or an impact summary. */
  children?: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Red confirm button for irreversible or financially significant actions. */
  destructive?: boolean;
  /**
   * When set, the operator must type this exact string to enable the confirm button.
   * Use it for case submission and anything else that leaves the platform.
   */
  requireTypedConfirmation?: string;
  /** Rejecting keeps the dialog open so the error stays visible next to the action. */
  onConfirm: () => void | Promise<void>;
}

/**
 * Confirmation gate for every human decision that signals a workflow: approve, reject, submit,
 * inject chaos. Contract 10 routes these into Temporal signals, so a mis-click is not free -
 * the dialog states what will happen before it happens.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  destructive = false,
  requireTypedConfirmation,
  onConfirm,
}: ConfirmDialogProps) {
  const [pending, setPending] = React.useState(false);
  const [typed, setTyped] = React.useState('');
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!open) {
      setTyped('');
      setError(null);
      setPending(false);
    }
  }, [open]);

  const confirmDisabled =
    pending || (requireTypedConfirmation ? typed.trim() !== requireTypedConfirmation : false);

  const handleConfirm = async () => {
    setPending(true);
    setError(null);
    try {
      await onConfirm();
      onOpenChange(false);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The action failed.');
    } finally {
      setPending(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description ? <DialogDescription>{description}</DialogDescription> : null}
        </DialogHeader>

        {children}

        {requireTypedConfirmation ? (
          <div className="space-y-1.5">
            <Label htmlFor="confirm-input">
              Type <span className="mono-id normal-case">{requireTypedConfirmation}</span> to confirm
            </Label>
            <Input
              id="confirm-input"
              value={typed}
              onChange={(event) => setTyped(event.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
          </div>
        ) : null}

        {error ? (
          <p className="text-sm" style={{ color: 'var(--status-critical)' }}>
            {error}
          </p>
        ) : null}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
            {cancelLabel}
          </Button>
          <Button
            variant={destructive ? 'destructive' : 'default'}
            onClick={handleConfirm}
            disabled={confirmDisabled}
          >
            {pending ? <Loader2 className="size-4 animate-spin" /> : null}
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
