'use client';

import * as React from 'react';
import {
  ArrowRight,
  Check,
  CircleHelp,
  Minus,
  ShieldCheck,
  ShieldQuestion,
  ShieldX,
  X,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { humanizeEnum } from '@/lib/format/id';
import type { SafetyDecision } from '@/lib/types/ai';
import type { CaseXRay } from '@/lib/types/case';
import type { PolicyView } from '@/lib/types/policy';
import { evaluateGateChecklist, type RuleOutcome } from './safetyRules';

const DECISION_ICON: Readonly<Record<SafetyDecision, LucideIcon>> = {
  ALLOW: ShieldCheck,
  ALLOW_WITH_REVIEW: ShieldQuestion,
  DENY: ShieldX,
};

const DECISION_COLOR: Readonly<Record<SafetyDecision, string>> = {
  ALLOW: 'var(--status-good)',
  ALLOW_WITH_REVIEW: 'var(--status-warning)',
  DENY: 'var(--status-critical)',
};

const DECISION_MEANING: Readonly<Record<SafetyDecision, string>> = {
  ALLOW:
    'Every rule passed. The recommended action may proceed on the deterministic path without a person.',
  ALLOW_WITH_REVIEW:
    'The result is usable but not final: the workflow parks on the humanDecision signal before anything leaves the platform.',
  DENY:
    'The result was rejected. The case is routed to AWAITING_HUMAN_REVIEW and the rejection is audited.',
};

const OUTCOME_ICON: Readonly<Record<RuleOutcome, LucideIcon>> = {
  pass: Check,
  fail: X,
  'not-applicable': Minus,
  unknown: CircleHelp,
};

const OUTCOME_COLOR: Readonly<Record<RuleOutcome, string>> = {
  pass: 'var(--status-good)',
  fail: 'var(--status-critical)',
  'not-applicable': 'var(--status-neutral)',
  unknown: 'var(--status-warning)',
};

const OUTCOME_LABEL: Readonly<Record<RuleOutcome, string>> = {
  pass: 'Passed',
  fail: 'Failed',
  'not-applicable': 'Not applicable',
  unknown: 'Not evaluated',
};

export interface SafetyGateTabProps {
  xray: CaseXRay;
  policy: PolicyView | null;
}

/**
 * The argument this product exists to make.
 *
 * Left: what the model proposed. Right: what the platform decided. Between them, seven
 * deterministic rules from contract 9.3, each shown with the value it was applied to. If the
 * reader takes one thing away it should be that the arrow points one way: a model proposal is
 * an input to a rule engine, never an output of the platform.
 */
export function SafetyGateTab({ xray, policy }: SafetyGateTabProps) {
  const verdict = xray.safetyVerdict;
  const result = xray.investigation;
  const checklist = React.useMemo(() => evaluateGateChecklist(xray, policy), [xray, policy]);

  const decision = verdict?.decision ?? null;
  const DecisionIcon = decision ? DECISION_ICON[decision] : ShieldQuestion;
  const decisionColor = decision ? DECISION_COLOR[decision] : 'var(--status-neutral)';

  return (
    <div className="space-y-5">
      <section
        className="rounded-lg border p-5"
        style={{
          borderColor: `color-mix(in oklab, ${decisionColor} 40%, transparent)`,
          backgroundColor: `color-mix(in oklab, ${decisionColor} 7%, transparent)`,
        }}
        aria-label="Safety verdict"
      >
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center">
          <div className="flex-1">
            <p className="text-2xs uppercase tracking-wide text-muted-foreground">
              What the model proposed
            </p>
            {result ? (
              <div className="mt-1.5 space-y-1">
                <p className="text-lg font-semibold text-foreground">
                  {humanizeEnum(result.recommendedAction)}
                </p>
                <p className="text-xs text-muted-foreground">
                  classified <span className="font-medium">{humanizeEnum(result.classification)}</span>{' '}
                  at {(result.confidence * 100).toFixed(1)}% self-reported confidence
                </p>
              </div>
            ) : (
              <p className="mt-1.5 text-sm text-muted-foreground">
                Nothing. The case never reached the reasoner.
              </p>
            )}
          </div>

          <ArrowRight className="hidden size-5 shrink-0 text-muted-foreground lg:block" aria-hidden />

          <div className="flex-1 lg:text-right">
            <p className="text-2xs uppercase tracking-wide text-muted-foreground">
              What the platform decided
            </p>
            <div className="mt-1.5 flex items-center gap-2 lg:justify-end">
              <DecisionIcon className="size-6" style={{ color: decisionColor }} aria-hidden />
              <span className="text-2xl font-semibold leading-none" style={{ color: decisionColor }}>
                {decision ?? 'NO VERDICT'}
              </span>
            </div>
            <p className="mt-1.5 max-w-md text-xs text-muted-foreground lg:ml-auto">
              {decision
                ? DECISION_MEANING[decision]
                : 'No SafetyVerdict is stored for this case, which means validateAndGate has not run yet.'}
            </p>
          </div>
        </div>

        <p className="mt-4 border-t pt-3 text-xs text-muted-foreground" style={{ borderColor: `color-mix(in oklab, ${decisionColor} 25%, transparent)` }}>
          The verdict above was produced by <span className="mono-id">AiResultValidator</span> and{' '}
          <span className="mono-id">SafetyGate</span> inside{' '}
          <span className="mono-id">evidence-core</span>, server-side and audited. The console
          displays it; it never computes it.
        </p>
      </section>

      {verdict && verdict.reasons.length > 0 ? (
        <section className="surface-card p-4" aria-label="Recorded reasons">
          <h2 className="text-sm font-semibold text-foreground">Reasons recorded by the gate</h2>
          <ul className="mt-2 space-y-1.5">
            {verdict.reasons.map((reason, index) => (
              <li key={index} className="flex items-start gap-2 text-sm">
                <span
                  className="mt-1.5 size-1.5 shrink-0 rounded-full"
                  style={{ backgroundColor: decisionColor }}
                  aria-hidden
                />
                <span className="text-foreground">{reason}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {verdict && verdict.unsupportedClaims.length > 0 ? (
        <section
          className="rounded-lg border border-[color:var(--status-critical)]/45 bg-[color:var(--status-critical)]/8 p-4"
          aria-label="Unsupported claims"
        >
          <h2 className="text-sm font-semibold text-foreground">
            {verdict.unsupportedClaims.length} unsupported claim
            {verdict.unsupportedClaims.length === 1 ? '' : 's'}
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">
            Each of these was asserted without a resolvable artifact behind it. Every one of them
            increments <span className="mono-id">pdei_ai_unsupported_claims_total</span>.
          </p>
          <ul className="mt-2 space-y-1.5 text-sm">
            {verdict.unsupportedClaims.map((claim, index) => (
              <li key={index} className="flex items-start gap-2">
                <ShieldX
                  className="mt-0.5 size-4 shrink-0"
                  style={{ color: 'var(--status-critical)' }}
                  aria-hidden
                />
                <span className="text-foreground">{claim}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section className="surface-card p-4" aria-label="Contract 9.3 rule checklist">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-sm font-semibold text-foreground">
            The seven rules
          </h2>
          <span className="text-2xs text-muted-foreground">
            {checklist.rules.filter((rule) => rule.outcome === 'pass').length} passed ·{' '}
            {checklist.failed.length} failed ·{' '}
            {checklist.rules.filter((rule) => rule.outcome === 'not-applicable').length} not
            applicable
          </span>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          Any single rule matching rejects the whole result. Re-evaluated here from the stored
          payload so the arithmetic is visible; the verdict above remains authoritative.
        </p>

        <ol className="mt-3 space-y-2">
          {checklist.rules.map((rule) => {
            const Icon = OUTCOME_ICON[rule.outcome];
            const color = OUTCOME_COLOR[rule.outcome];
            return (
              <li
                key={rule.id}
                className={cn(
                  'rounded-md border p-3',
                  rule.outcome === 'fail'
                    ? 'border-[color:var(--status-critical)]/45 bg-[color:var(--status-critical)]/8'
                    : 'border-border',
                )}
              >
                <div className="flex items-start gap-3">
                  <span
                    className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full border"
                    style={{ borderColor: `color-mix(in oklab, ${color} 45%, transparent)`, color }}
                  >
                    <Icon className="size-3" aria-hidden />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="tabular text-2xs text-muted-foreground">
                        Rule {rule.ordinal}
                      </span>
                      <span className="text-2xs font-medium" style={{ color }}>
                        {OUTCOME_LABEL[rule.outcome]}
                      </span>
                    </div>
                    <p className="mt-0.5 text-sm text-foreground">
                      Reject when: {rule.statement}
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">{rule.rationale}</p>
                    <p className="mt-1 text-2xs text-muted-foreground">
                      Observed: <span className="text-foreground">{rule.observed}</span>
                    </p>
                  </div>
                </div>
              </li>
            );
          })}
        </ol>

        {checklist.incomplete ? (
          <p className="mt-3 text-2xs" style={{ color: 'var(--status-warning)' }}>
            One or more rules could not be re-evaluated because no applicable policy was loaded for{' '}
            <span className="mono-id">{xray.merchantId}</span> /{' '}
            <span className="mono-id">{xray.reasonCode}</span>. The gate itself always has a
            policy; the console may not.
          </p>
        ) : null}
      </section>

      <section className="surface-card p-4" aria-label="What the gate protects">
        <h2 className="text-sm font-semibold text-foreground">Why this screen exists</h2>
        <ul className="mt-2 space-y-1.5 text-xs text-muted-foreground">
          <li>
            <span className="font-medium text-foreground">The LLM is never the source of truth.</span>{' '}
            Classification and confidence are inputs to the rules above, nothing more.
          </li>
          <li>
            <span className="font-medium text-foreground">The LLM never mutates financial state.</span>{' '}
            Evidence, readiness, packages and submissions are written only by Java services.
          </li>
          <li>
            <span className="font-medium text-foreground">Unsupported claims are rejected.</span>{' '}
            A citation that does not resolve to an attached artifact fails rule 1 outright.
          </li>
          <li>
            Every rejection is routed to <StatusBadge kind="dispute" value="AWAITING_HUMAN_REVIEW" />{' '}
            and written to the hash-chained audit log.
          </li>
        </ul>
        {policy ? (
          <div className="mt-3 flex flex-wrap gap-2 border-t border-border pt-3 text-2xs">
            <Badge variant="subtle" className="mono-id">
              {policy.policyVersionId}
            </Badge>
            <Badge variant="outline">
              autoPrepareMinConfidence {policy.autoPrepareMinConfidence}
            </Badge>
            <Badge variant="outline">maxContradictions {policy.maxContradictions}</Badge>
            <Badge variant="outline">
              minReadinessScoreForAutoPrepare {policy.minReadinessScoreForAutoPrepare}
            </Badge>
            <Badge variant="outline">
              autoSubmit {policy.autoSubmitEnabled ? 'enabled' : 'disabled'}
            </Badge>
          </div>
        ) : null}
      </section>
    </div>
  );
}
