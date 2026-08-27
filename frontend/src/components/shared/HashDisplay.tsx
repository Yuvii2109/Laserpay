'use client';

import { useState } from 'react';
import { Check, Copy, Fingerprint } from 'lucide-react';
import { cn } from '@/lib/utils';
import { shortenHash } from '@/lib/format/id';
import { toast } from '@/components/ui/sonner';

export interface HashDisplayProps {
  sha256: string | null | undefined;
  /** Render the whole 64-char digest instead of the `a3f9…c1` short form. */
  full?: boolean;
  /** Accessible prefix, e.g. "Expected sha256". */
  label?: string;
  /** Show the fingerprint icon before the digest. */
  withIcon?: boolean;
  className?: string;
}

/**
 * A sha256 with a copy control.
 *
 * Integrity is the whole point of contract 11's `x-amz-meta-sha256`, so the digest is always
 * copyable in full even when it is displayed short: an operator comparing a stored hash with
 * a recomputed one must be able to paste both somewhere, not retype forty characters.
 */
export function HashDisplay({
  sha256,
  full = false,
  label = 'sha256',
  withIcon = false,
  className,
}: HashDisplayProps) {
  const [copied, setCopied] = useState(false);

  if (!sha256) {
    return <span className={cn('text-muted-foreground', className)}>—</span>;
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(sha256);
      setCopied(true);
      toast.success(`${label} copied`);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error('Clipboard is unavailable in this browser');
    }
  };

  return (
    <span className={cn('group inline-flex min-w-0 items-center gap-1.5', className)}>
      {withIcon ? <Fingerprint className="size-3.5 shrink-0 text-muted-foreground" aria-hidden /> : null}
      <span className={cn('mono-id min-w-0 text-foreground', full && 'break-all')} title={sha256}>
        {full ? sha256 : shortenHash(sha256)}
      </span>
      <button
        type="button"
        onClick={copy}
        aria-label={`Copy ${label}`}
        className="shrink-0 rounded p-0.5 text-muted-foreground transition-opacity hover:text-foreground focus-visible:opacity-100 md:opacity-0 md:group-hover:opacity-100"
      >
        {copied ? <Check className="size-3.5" aria-hidden /> : <Copy className="size-3.5" aria-hidden />}
      </button>
    </span>
  );
}
