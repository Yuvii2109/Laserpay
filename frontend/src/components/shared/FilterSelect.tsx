'use client';

import { cn } from '@/lib/utils';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

export interface FilterOption {
  value: string;
  label: string;
}

export interface FilterSelectProps {
  /** Required: the label is bound to the trigger with `htmlFor`/`id`. */
  id: string;
  label: string;
  /** `undefined` means "no filter" and shows the `allLabel` option. */
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  options: readonly FilterOption[];
  allLabel?: string;
  className?: string;
  triggerClassName?: string;
}

/**
 * A labelled enum facet.
 *
 * Radix's Select cannot hold an empty string as an item value, so "no filter" is carried by
 * a sentinel and translated back to `undefined` at the boundary - list endpoints in contract
 * 8.1 treat a missing parameter as "unfiltered", and an empty string would be sent as one.
 */
const ALL = '__all__';

export function FilterSelect({
  id,
  label,
  value,
  onChange,
  options,
  allLabel = 'All',
  className,
  triggerClassName,
}: FilterSelectProps) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-1.5', className)}>
      <Label htmlFor={id}>{label}</Label>
      <Select
        value={value ?? ALL}
        onValueChange={(next) => onChange(next === ALL ? undefined : next)}
      >
        <SelectTrigger id={id} className={cn('h-9 w-48', triggerClassName)}>
          <SelectValue placeholder={allLabel} />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>{allLabel}</SelectItem>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
