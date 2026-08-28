'use client';

import {
  Check,
  CircleDashed,
  CircleDot,
  Hourglass,
  OctagonX,
  SkipForward,
  Timer,
  Zap,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Progress } from '@/components/ui/progress';
import type { CaseStatus } from '@/lib/types/case';
import {
  STEP_STATE_LABEL,
  WORKFLOW_STEPS,
  progressFraction,
  stepStateFor,
  type StepState,
  type WorkflowStepKind,
} from './workflow';

const STATE_ICON: Readonly<Record<StepState, LucideIcon>> = {
  done: Check,
  active: CircleDot,
  pending: CircleDashed,
  skipped: SkipForward,
  failed: OctagonX,
  unknown: CircleDashed,
};

const STATE_COLOR: Readonly<Record<StepState, string>> = {
  done: 'var(--status-good)',
  active: 'var(--chart-1)',
  pending: 'var(--status-neutral)',
  skipped: 'var(--status-neutral)',
  failed: 'var(--status-critical)',
  unknown: 'var(--status-neutral)',
};

const KIND_ICON: Readonly<Record<WorkflowStepKind, LucideIcon>> = {
  activity: Zap,
  signal: Hourglass,
  timer: Timer,
};

export interface WorkflowStepperProps {
  status: CaseStatus;
  bypassedAi: boolean;
  workflowId: string | null;
  className?: string;
}

/**
 * The twelve steps of `DisputeCaseWorkflow` (contract 10) with the case's position on them.
 *
 * Every step carries its state as an icon *and* a word, so progress is legible in greyscale
 * and to a screen reader. Step 6 (`investigate`) renders as skipped whenever admission control
 * short-circuited the case, which is the whole point: most cases never reach the model.
 */
export function WorkflowStepper({ status, bypassedAi, workflowId, className }: WorkflowStepperProps) {
  const fraction = progressFraction(status);
  const failed = status === 'FAILED';

  return (
    <section className={cn('surface-card p-4', className)} aria-label="Workflow progress">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-foreground">Workflow progress</h2>
        <span className="mono-id text-2xs text-muted-foreground">
          {workflowId ?? 'workflow id unavailable'}
        </span>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        Temporal namespace <span className="mono-id">pdei</span>, task queue{' '}
        <span className="mono-id">pdei-dispute-cases</span>.
      </p>

      <Progress
        className="mt-3"
        value={fraction * 100}
        indicatorColor={failed ? 'var(--status-critical)' : 'var(--chart-1)'}
        aria-label="Workflow completion"
      />

      <ol className="mt-4 space-y-0">
        {WORKFLOW_STEPS.map((step, index) => {
          const state = stepStateFor(step, { status, bypassedAi });
          const Icon = STATE_ICON[state];
          const KindIcon = KIND_ICON[step.kind];
          const color = STATE_COLOR[state];
          const last = index === WORKFLOW_STEPS.length - 1;

          return (
            <li key={step.id} className="relative flex gap-3 pb-3 last:pb-0">
              {!last ? (
                <span
                  className="absolute left-[0.6875rem] top-6 h-[calc(100%-1.25rem)] w-px"
                  style={{
                    backgroundColor:
                      state === 'done' ? 'var(--status-good)' : 'hsl(var(--border))',
                  }}
                  aria-hidden
                />
              ) : null}

              <span
                className="relative z-10 mt-0.5 flex size-[1.375rem] shrink-0 items-center justify-center rounded-full border bg-card"
                style={{ borderColor: `color-mix(in oklab, ${color} 45%, transparent)`, color }}
              >
                <Icon className="size-3" aria-hidden />
              </span>

              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                  <span className="tabular text-2xs text-muted-foreground">{step.ordinal}.</span>
                  <span
                    className={cn(
                      'mono-id text-[0.8125rem]',
                      state === 'pending' || state === 'unknown'
                        ? 'text-muted-foreground'
                        : 'text-foreground',
                    )}
                  >
                    {step.label}
                  </span>
                  <span
                    className="inline-flex items-center gap-1 text-2xs text-muted-foreground"
                    title={`${step.kind} step`}
                  >
                    <KindIcon className="size-3" aria-hidden />
                    {step.kind}
                  </span>
                  <span className="text-2xs font-medium" style={{ color }}>
                    {STEP_STATE_LABEL[state]}
                  </span>
                </div>
                <p className="mt-0.5 text-2xs leading-snug text-muted-foreground">{step.detail}</p>
                {state === 'skipped' ? (
                  <p className="mt-0.5 text-2xs" style={{ color: 'var(--status-neutral)' }}>
                    Bypassed by a deterministic short-circuit. No model call was
                    made and no tokens were spent.
                  </p>
                ) : null}
              </div>
            </li>
          );
        })}
      </ol>

      {failed ? (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-critical)' }}>
          The workflow failed. Temporal does not report which step failed through the gateway, so
          the positions above are unknown; open the workflow in the Temporal UI at
          localhost:8233 for the activity history.
        </p>
      ) : null}
    </section>
  );
}
