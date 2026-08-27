'use client';

import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import { formatInstant, formatRelative } from '@/lib/format/date';
import { useUiStore } from '@/lib/store/uiStore';
import type { Iso8601 } from '@/lib/types/common';

export interface TimestampDisplayProps {
  value: Iso8601 | null | undefined;
  /**
   * `relative` -> "6m ago", `absolute` -> "26 Aug 2026, 10:15:30 UTC",
   * `both` -> relative with the absolute form in the title.
   */
  mode?: 'relative' | 'absolute' | 'both';
  className?: string;
  muted?: boolean;
}

/**
 * Renders an instant. Absolute renderings honour the operator's UTC/local preference and are
 * labelled with the zone, because a capture time that silently shifts is an audit problem.
 *
 * Relative renderings are computed after mount: the server and the browser disagree about
 * "now" by definition, and a hydration mismatch on a timestamp is not worth the flicker.
 */
export function TimestampDisplay({
  value,
  mode = 'both',
  className,
  muted = false,
}: TimestampDisplayProps) {
  const timeZoneMode = useUiStore((state) => state.timeZoneMode);
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  if (!value) return <span className={cn('text-muted-foreground', className)}>—</span>;

  const absolute = formatInstant(value, timeZoneMode);
  const showRelative = mounted && mode !== 'absolute';

  return (
    <time
      dateTime={value}
      title={absolute}
      className={cn('whitespace-nowrap', muted && 'text-muted-foreground', className)}
    >
      {showRelative ? formatRelative(value) : absolute}
    </time>
  );
}
