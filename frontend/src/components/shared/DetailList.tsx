import { cn } from '@/lib/utils';

export interface DetailItem {
  label: string;
  value: React.ReactNode;
  /** Small explanatory line under the value. */
  hint?: React.ReactNode;
  /** Span both columns in the two-column layout (long values, object keys). */
  wide?: boolean;
}

export interface DetailListProps {
  items: DetailItem[];
  className?: string;
  /** 1 for a narrow side panel, 2 for a page-width panel. */
  columns?: 1 | 2;
}

/**
 * A `<dl>` of labelled facts. Detail pages are mostly this, so it exists once: the label
 * column stays uppercase-muted and the value column keeps whatever component rendered it
 * (MoneyDisplay, TimestampDisplay, CopyableId), which is what keeps money and time honest.
 */
export function DetailList({ items, className, columns = 2 }: DetailListProps) {
  return (
    <dl
      className={cn(
        'grid gap-x-6 gap-y-3.5',
        columns === 2 ? 'sm:grid-cols-2' : 'grid-cols-1',
        className,
      )}
    >
      {items.map((item) => (
        <div key={item.label} className={cn('min-w-0 space-y-1', item.wide && 'sm:col-span-2')}>
          <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {item.label}
          </dt>
          <dd className="min-w-0 text-sm text-foreground">{item.value}</dd>
          {item.hint ? <p className="text-xs text-muted-foreground">{item.hint}</p> : null}
        </div>
      ))}
    </dl>
  );
}
