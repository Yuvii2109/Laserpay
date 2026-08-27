import type { LucideIcon } from 'lucide-react';
import { ArrowDownRight, ArrowRight, ArrowUpRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Card } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { toneColorVar, type Tone } from '@/lib/format/score';

export interface StatTileDelta {
  /** Signed change; the sign picks the arrow, `goodDirection` picks the colour. */
  value: number;
  label?: string;
  /** Which direction is a good outcome for this metric. Defaults to `up`. */
  goodDirection?: 'up' | 'down' | 'neutral';
  format?: (value: number) => string;
}

export interface StatTileProps {
  label: string;
  /** Pre-formatted. Money must arrive already through MoneyDisplay/formatMoney. */
  value: React.ReactNode;
  hint?: React.ReactNode;
  icon?: LucideIcon;
  /** Reserved status tone; use it only when the tile really reports a state. */
  tone?: Tone;
  delta?: StatTileDelta;
  loading?: boolean;
  className?: string;
  footer?: React.ReactNode;
}

/**
 * A KPI tile: one number, one label, optional change.
 *
 * A single headline value is not a chart - it is a number, set large, with its label. No
 * sparkline is drawn unless a page has a real series to show, and the figure uses proportional
 * figures rather than tabular ones because it stands alone.
 */
export function StatTile({
  label,
  value,
  hint,
  icon: Icon,
  tone,
  delta,
  loading = false,
  className,
  footer,
}: StatTileProps) {
  const accent = tone ? toneColorVar(tone) : undefined;

  return (
    <Card className={cn('flex flex-col gap-3 p-4', className)}>
      <div className="flex items-start justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {label}
        </span>
        {Icon ? (
          <Icon className="size-4 shrink-0 text-muted-foreground" style={accent ? { color: accent } : undefined} aria-hidden />
        ) : null}
      </div>

      {loading ? (
        <Skeleton className="h-8 w-24" />
      ) : (
        <div className="flex items-end gap-2">
          <span
            className="text-2xl font-semibold leading-none tracking-tight"
            style={accent ? { color: accent } : undefined}
          >
            {value}
          </span>
          {delta ? <DeltaChip delta={delta} /> : null}
        </div>
      )}

      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      {footer}
    </Card>
  );
}

function DeltaChip({ delta }: { delta: StatTileDelta }) {
  const { value, label, goodDirection = 'up', format } = delta;
  const rising = value > 0;
  const flat = value === 0;
  const good =
    goodDirection === 'neutral' ? null : goodDirection === 'up' ? rising : !rising && !flat;
  const color = flat || good === null ? 'var(--status-neutral)' : good ? 'var(--status-good)' : 'var(--status-critical)';
  const Icon = flat ? ArrowRight : rising ? ArrowUpRight : ArrowDownRight;
  const rendered = format ? format(value) : `${rising ? '+' : ''}${value}`;

  return (
    <span className="inline-flex items-center gap-1 pb-0.5 text-xs font-medium" style={{ color }}>
      <Icon className="size-3.5" aria-hidden />
      <span className="tabular">{rendered}</span>
      {label ? <span className="text-muted-foreground">{label}</span> : null}
    </span>
  );
}
