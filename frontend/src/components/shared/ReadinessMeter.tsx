import { cn } from '@/lib/utils';
import {
  BAND_LABEL,
  BAND_THRESHOLDS,
  bandColorVar,
  bandFromScore,
  formatScore,
  scoreFraction,
} from '@/lib/format/score';
import type { ReadinessBand } from '@/lib/types/readiness';

export interface ReadinessMeterProps {
  score: number | null | undefined;
  band?: ReadinessBand | null;
  className?: string;
  /** `bar` is the table/detail form; `hero` is the big control-tower figure. */
  variant?: 'bar' | 'hero';
  /** Draw ticks at the 50 / 75 / 90 band boundaries. */
  showThresholds?: boolean;
  /** Optional caption under the meter, e.g. "recomputed 4m ago". */
  caption?: string;
}

/**
 * A single-value readiness meter, 0-100.
 *
 * It is a magnitude on a fixed scale, so it is a bar and not a gauge or a donut: the
 * comparison a reader makes is "how far along the 0-100 scale", which length answers directly.
 * The band boundaries are drawn as ticks so a score can be read against the thresholds that
 * actually change behaviour (contract 6), rather than against a colour alone.
 */
export function ReadinessMeter({
  score,
  band,
  className,
  variant = 'bar',
  showThresholds = true,
  caption,
}: ReadinessMeterProps) {
  const resolvedBand = band ?? bandFromScore(score);
  const fraction = scoreFraction(score);
  const color = bandColorVar(resolvedBand);
  const scored = score !== null && score !== undefined && Number.isFinite(score);

  return (
    <div className={cn('w-full', className)} data-band={resolvedBand ?? 'UNSCORED'}>
      <div className="flex items-baseline justify-between gap-3">
        <span
          className={cn(
            'font-semibold leading-none',
            variant === 'hero' ? 'text-3xl' : 'text-sm tabular',
          )}
          style={{ color: scored ? color : 'hsl(var(--muted-foreground))' }}
        >
          {scored ? formatScore(score) : '-'}
          {variant === 'hero' ? <span className="ml-1 text-base font-normal opacity-70">/100</span> : null}
        </span>
        <span className="text-xs font-medium text-muted-foreground">
          {resolvedBand ? BAND_LABEL[resolvedBand] : 'Not scored'}
        </span>
      </div>

      <div
        className={cn('relative mt-2 w-full overflow-hidden rounded-full bg-muted', variant === 'hero' ? 'h-2.5' : 'h-1.5')}
        role="meter"
        aria-valuenow={scored ? Math.round(score) : undefined}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Evidence readiness"
      >
        <div
          className="h-full rounded-full transition-[width] duration-500"
          style={{ width: `${fraction * 100}%`, backgroundColor: color }}
        />
        {showThresholds
          ? BAND_THRESHOLDS.map((threshold) => (
              <span
                key={threshold.band}
                className="absolute top-0 h-full w-px bg-card/80"
                style={{ left: `${threshold.at * 100}%` }}
                aria-hidden
              />
            ))
          : null}
      </div>

      {showThresholds && variant === 'hero' ? (
        <div className="mt-1 flex justify-between text-2xs text-muted-foreground">
          <span>0</span>
          <span>50</span>
          <span>75</span>
          <span>90</span>
          <span>100</span>
        </div>
      ) : null}

      {caption ? <p className="mt-1.5 text-xs text-muted-foreground">{caption}</p> : null}
    </div>
  );
}
