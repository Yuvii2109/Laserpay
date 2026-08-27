import Link from 'next/link';
import { ArrowRight, type LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface PanelCardProps {
  title: string;
  /** One line on what the panel answers. */
  description?: string;
  icon?: LucideIcon;
  /** Right-aligned "see all" destination. */
  href?: string;
  hrefLabel?: string;
  /** Small count or status rendered beside the title. */
  meta?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  bodyClassName?: string;
}

/**
 * A titled panel on the Control Tower. Every panel is a `<section>` with its heading bound to
 * it, so the page is navigable by landmark and the tower reads as a list of questions rather
 * than a wall of cards.
 */
export function PanelCard({
  title,
  description,
  icon: Icon,
  href,
  hrefLabel = 'See all',
  meta,
  children,
  className,
  bodyClassName,
}: PanelCardProps) {
  return (
    <section
      aria-label={title}
      className={cn('flex min-w-0 flex-col rounded-lg border border-border bg-card', className)}
    >
      <header className="flex flex-wrap items-start justify-between gap-2 border-b border-border px-4 py-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
            {Icon ? <Icon className="size-4 text-muted-foreground" aria-hidden /> : null}
            {title}
            {meta ? <span className="text-xs font-normal text-muted-foreground">{meta}</span> : null}
          </h2>
          {description ? (
            <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {href ? (
          <Link
            href={href}
            className="inline-flex shrink-0 items-center gap-1 text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            {hrefLabel}
            <ArrowRight className="size-3" aria-hidden />
          </Link>
        ) : null}
      </header>
      <div className={cn('min-w-0 flex-1 p-4', bodyClassName)}>{children}</div>
    </section>
  );
}
