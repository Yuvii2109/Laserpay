'use client';

import Link from 'next/link';
import { ArrowRight, FlaskConical, ShieldAlert, TriangleAlert } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { MerchantSummary } from '@/lib/types/merchant';

/**
 * "Start here" strip at the top of the Control Tower.
 *
 * The console is merchant-scoped and every screen is reachable from the sidebar, which is
 * fine once you know the product and useless when you do not: the first question a new
 * viewer has is not "what is my average readiness" but "what am I supposed to do here".
 *
 * So this reads the same summary the tiles read and turns it into at most three concrete
 * next actions, each one a real number and a link to the screen that acts on it. When
 * nothing needs attention it says so rather than inventing work, because an empty queue is
 * the product working, not the page failing.
 */
export interface NextStepsProps {
  summary: MerchantSummary | undefined;
}

interface Step {
  icon: LucideIcon;
  count: number;
  label: string;
  href: string;
  action: string;
  tone: 'serious' | 'warning' | 'neutral';
}

const TONE: Record<Step['tone'], string> = {
  serious: 'text-rose-400',
  warning: 'text-amber-400',
  neutral: 'text-sky-400',
};

export function NextSteps({ summary }: NextStepsProps) {
  if (!summary) {
    return null;
  }

  const steps: Step[] = [];

  if (summary.blockingGaps > 0) {
    steps.push({
      icon: TriangleAlert,
      count: summary.blockingGaps,
      label: summary.blockingGaps === 1 ? 'evidence gap needs fixing' : 'evidence gaps need fixing',
      href: '/gaps',
      action: 'Review gaps',
      tone: 'serious',
    });
  }

  if (summary.openDisputes > 0) {
    steps.push({
      icon: ShieldAlert,
      count: summary.openDisputes,
      label: summary.openDisputes === 1 ? 'dispute is open' : 'disputes are open',
      href: '/cases',
      action: 'Open case queue',
      tone: 'warning',
    });
  }

  // Always offered: the simulator is how you make something happen on an idle console.
  steps.push({
    icon: FlaskConical,
    count: 0,
    label: 'Generate more activity',
    href: '/simulation',
    action: 'Run a scenario',
    tone: 'neutral',
  });

  return (
    <section
      aria-label="Suggested next steps"
      className="mb-4 rounded-lg border border-border/60 bg-card/40 p-3"
    >
      <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
        Start here
      </p>
      <ul className="grid gap-2 sm:grid-cols-3">
        {steps.slice(0, 3).map((step) => (
          <li key={step.href}>
            <Link
              href={step.href}
              className="group flex items-center justify-between gap-3 rounded-md border border-border/50
                         bg-background/40 px-3 py-2 transition hover:border-border hover:bg-background/70"
            >
              <span className="flex min-w-0 items-center gap-2">
                <step.icon className={`h-4 w-4 shrink-0 ${TONE[step.tone]}`} aria-hidden />
                <span className="min-w-0 truncate text-sm">
                  {step.count > 0 ? (
                    <>
                      <span className="font-semibold tabular-nums">{step.count}</span>{' '}
                      <span className="text-muted-foreground">{step.label}</span>
                    </>
                  ) : (
                    <span className="text-muted-foreground">{step.label}</span>
                  )}
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground group-hover:text-foreground">
                {step.action}
                <ArrowRight className="h-3 w-3" aria-hidden />
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
