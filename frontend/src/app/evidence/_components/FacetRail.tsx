'use client';

import { cn } from '@/lib/utils';

export interface FacetRailOption {
  value: string;
  label: string;
  icon?: React.ReactNode;
}

export interface FacetRailProps {
  /** Group label; rendered as the accessible name of the toggle group. */
  label: string;
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  options: readonly FacetRailOption[];
  allLabel?: string;
  className?: string;
}

/**
 * A single-select facet as a row of toggles.
 *
 * Facets, not filters: the whole vocabulary is visible at once, which is what makes an explorer
 * explorable. Counts are deliberately absent — the gateway exposes no aggregation endpoint, and
 * a count computed from the current page would be a number that looks authoritative and is not.
 *
 * `aria-pressed` carries the state, so the selection survives greyscale and forced-colors mode.
 */
export function FacetRail({
  label,
  value,
  onChange,
  options,
  allLabel = 'All',
  className,
}: FacetRailProps) {
  return (
    <div className={cn('min-w-0', className)}>
      <p className="pb-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground" id={`facet-${slug(label)}`}>
        {label}
      </p>
      <div className="flex flex-wrap gap-1.5" role="group" aria-labelledby={`facet-${slug(label)}`}>
        <FacetChip
          pressed={value === undefined}
          onClick={() => onChange(undefined)}
          label={allLabel}
        />
        {options.map((option) => (
          <FacetChip
            key={option.value}
            pressed={value === option.value}
            onClick={() => onChange(value === option.value ? undefined : option.value)}
            label={option.label}
            icon={option.icon}
          />
        ))}
      </div>
    </div>
  );
}

function FacetChip({
  pressed,
  onClick,
  label,
  icon,
}: {
  pressed: boolean;
  onClick: () => void;
  label: string;
  icon?: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-medium transition-colors',
        pressed
          ? 'border-primary bg-primary/10 text-primary'
          : 'border-border bg-card text-muted-foreground hover:bg-accent hover:text-foreground',
      )}
    >
      {icon}
      {label}
    </button>
  );
}

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-');
}
