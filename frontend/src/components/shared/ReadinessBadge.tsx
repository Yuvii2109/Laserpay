import { AlertTriangle, CircleCheck, OctagonX, TriangleAlert } from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  BAND_DESCRIPTION,
  BAND_LABEL,
  bandColorVar,
  bandFromScore,
  formatScore,
} from '@/lib/format/score';
import type { ReadinessBand } from '@/lib/types/readiness';

const BAND_ICON = {
  READY: CircleCheck,
  NEARLY_READY: AlertTriangle,
  AT_RISK: TriangleAlert,
  NOT_READY: OctagonX,
} as const;

export interface ReadinessBadgeProps {
  /** Either is enough; when both are given, `band` wins and `score` is displayed. */
  band?: ReadinessBand | null;
  score?: number | null;
  className?: string;
  /** Show the numeric score beside the band label. */
  showScore?: boolean;
  size?: 'sm' | 'md';
}

/**
 * Readiness band badge. Band colour comes from the reserved status ramp and is always paired
 * with the band name, so the state is never carried by colour alone.
 */
export function ReadinessBadge({
  band,
  score,
  className,
  showScore = true,
  size = 'md',
}: ReadinessBadgeProps) {
  const resolved = band ?? bandFromScore(score);
  if (!resolved) {
    return <span className={cn('text-muted-foreground', className)}>Not scored</span>;
  }

  const Icon = BAND_ICON[resolved];
  const color = bandColorVar(resolved);

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border font-medium',
        size === 'sm' ? 'px-1.5 py-0.5 text-2xs' : 'px-2 py-0.5 text-xs',
        className,
      )}
      style={{
        color,
        borderColor: `color-mix(in oklab, ${color} 35%, transparent)`,
        backgroundColor: `color-mix(in oklab, ${color} 12%, transparent)`,
      }}
      title={BAND_DESCRIPTION[resolved]}
      data-band={resolved}
    >
      <Icon className="size-3.5 shrink-0" aria-hidden />
      <span>{BAND_LABEL[resolved]}</span>
      {showScore && score !== null && score !== undefined ? (
        <span className="tabular opacity-80">{formatScore(score)}</span>
      ) : null}
    </span>
  );
}
