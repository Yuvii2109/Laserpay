import { cn } from '@/lib/utils';

export interface PageHeaderProps {
  title: React.ReactNode;
  /** One sentence on what this page answers. */
  description?: React.ReactNode;
  /** Breadcrumb or back-link slot, rendered above the title. */
  eyebrow?: React.ReactNode;
  /** Buttons and filters, right-aligned on wide screens. */
  actions?: React.ReactNode;
  /** Badges rendered next to the title (status, band, id). */
  meta?: React.ReactNode;
  className?: string;
}

/** The standard page heading. Every route uses it so headings never drift. */
export function PageHeader({
  title,
  description,
  eyebrow,
  actions,
  meta,
  className,
}: PageHeaderProps) {
  return (
    <header className={cn('flex flex-col gap-3 pb-5 lg:flex-row lg:items-start lg:justify-between', className)}>
      <div className="min-w-0 space-y-1.5">
        {eyebrow ? <div className="text-xs text-muted-foreground">{eyebrow}</div> : null}
        <div className="flex flex-wrap items-center gap-2.5">
          <h1 className="truncate text-xl font-semibold tracking-tight text-foreground">{title}</h1>
          {meta}
        </div>
        {description ? (
          <p className="max-w-3xl text-sm text-muted-foreground">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </header>
  );
}
