'use client';

import { cn } from '@/lib/utils';
import { formatConfidence } from '@/lib/format/score';

export interface ConfidenceMeterProps {
  /** Model self-reported confidence in [0,1] (contract 9.2). */
  confidence: number;
  /** `policy.autoPrepareMinConfidence` - the floor below which auto-prepare is refused. */
  floor: number | null;
  /** Set when the result came from the deterministic path rather than a model. */
  deterministic?: boolean;
  className?: string;
}

/**
 * A calibrated confidence meter.
 *
 * Two decisions make this honest rather than decorative:
 *
 * 1. The policy floor (`autoPrepareMinConfidence`) is drawn on the scale, so a number is read
 *    against the threshold that actually changes behaviour - not against an intuition.
 * 2. The bar is never coloured "green because high". It takes the reserved status ramp only
 *    from its position relative to the floor, and the caption states in words what the number
 *    is: a model's self-report, not a probability of winning the dispute.
 */
export function ConfidenceMeter({
  confidence,
  floor,
  deterministic = false,
  className,
}: ConfidenceMeterProps) {
  const clamped = Math.min(1, Math.max(0, Number.isFinite(confidence) ? confidence : 0));
  const hasFloor = floor !== null && Number.isFinite(floor);
  const meetsFloor = hasFloor ? clamped >= (floor as number) : null;
  const color = deterministic
    ? 'var(--status-neutral)'
    : meetsFloor === null
      ? 'var(--chart-1)'
      : meetsFloor
        ? 'var(--status-good)'
        : 'var(--status-warning)';

  return (
    <div className={cn('w-full', className)}>
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-2xl font-semibold leading-none" style={{ color }}>
          {formatConfidence(clamped)}
        </span>
        <span className="text-xs font-medium text-muted-foreground">
          {deterministic
            ? 'deterministic certainty'
            : meetsFloor === null
              ? 'no policy floor loaded'
              : meetsFloor
                ? 'at or above the policy floor'
                : 'below the policy floor'}
        </span>
      </div>

      <div
        className="relative mt-2 h-2.5 w-full overflow-hidden rounded-full bg-muted"
        role="meter"
        aria-valuenow={Math.round(clamped * 100)}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Model confidence"
      >
        <div
          className="h-full rounded-full transition-[width] duration-500"
          style={{ width: `${clamped * 100}%`, backgroundColor: color }}
        />
        {hasFloor ? (
          <span
            className="absolute top-0 h-full w-0.5 bg-foreground"
            style={{ left: `${(floor as number) * 100}%` }}
            aria-hidden
          />
        ) : null}
      </div>

      <div className="relative mt-1 h-4 text-2xs text-muted-foreground">
        <span className="absolute left-0">0%</span>
        {hasFloor ? (
          <span
            className="absolute -translate-x-1/2 whitespace-nowrap tabular"
            style={{ left: `${(floor as number) * 100}%` }}
          >
            floor {formatConfidence(floor)}
          </span>
        ) : null}
        <span className="absolute right-0">100%</span>
      </div>

      <p className="mt-2 text-2xs leading-snug text-muted-foreground">
        {deterministic
          ? 'This case was resolved by the deterministic engine, so the figure is a constant, not an estimate.'
          : 'Confidence is the model’s own report about its answer. It is not a probability of winning the dispute and it is not evidence. It matters only because policy uses it as a threshold.'}
      </p>
    </div>
  );
}
