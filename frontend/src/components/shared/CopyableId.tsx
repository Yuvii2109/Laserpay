'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Check, Copy } from 'lucide-react';
import { cn } from '@/lib/utils';
import { hrefForId, shortenId } from '@/lib/format/id';
import { toast } from '@/components/ui/sonner';

export interface CopyableIdProps {
  id: string | null | undefined;
  /** Truncate the middle. Off for short ids like `TX-000042`. */
  shorten?: boolean;
  /** Link to the entity's detail route when one exists (resolved from the id prefix). */
  link?: boolean;
  className?: string;
  label?: string;
}

/**
 * A prefixed platform id (contract 5) with copy-to-clipboard, and an optional link to the
 * entity's own page. Operators copy ids into log queries constantly; make it one click.
 */
export function CopyableId({ id, shorten = false, link = true, className, label }: CopyableIdProps) {
  const [copied, setCopied] = useState(false);

  if (!id) return <span className={cn('text-muted-foreground', className)}>—</span>;

  const href = link ? hrefForId(id) : null;
  const text = shorten ? shortenId(id) : id;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(id);
      setCopied(true);
      toast.success(`Copied ${id}`);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error('Clipboard is unavailable in this browser');
    }
  };

  return (
    <span className={cn('group inline-flex items-center gap-1', className)}>
      {href ? (
        <Link href={href} className="mono-id text-foreground underline-offset-4 hover:underline" title={id}>
          {text}
        </Link>
      ) : (
        <span className="mono-id text-foreground" title={id}>
          {text}
        </span>
      )}
      <button
        type="button"
        onClick={copy}
        aria-label={label ?? `Copy ${id}`}
        className="rounded p-0.5 text-muted-foreground opacity-0 transition-opacity hover:text-foreground focus-visible:opacity-100 group-hover:opacity-100"
      >
        {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
      </button>
    </span>
  );
}
