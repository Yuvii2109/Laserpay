'use client';

import * as React from 'react';
import Link from 'next/link';
import {
  BadgeCheck,
  Bot,
  CircleSlash,
  Cpu,
  Link2Off,
  Quote,
  ShieldOff,
  SquareStack,
  Zap,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { CopyableId } from '@/components/shared/CopyableId';
import { EvidenceTypeIcon, EVIDENCE_TYPE_LABEL } from '@/components/shared/EvidenceTypeIcon';
import { formatLatency } from '@/lib/format/date';
import { humanizeEnum } from '@/lib/format/id';
import { activeShortCircuit } from '@/lib/types/ai';
import { EVIDENCE_TYPES } from '@/lib/types/evidence';
import type { EvidenceType } from '@/lib/types/evidence';
import type { AdmissionDecision, InvestigationClassification } from '@/lib/types/ai';
import type { CaseXRay } from '@/lib/types/case';
import type { PolicyView } from '@/lib/types/policy';
import { ConfidenceMeter } from './ConfidenceMeter';
import { deriveBypass } from './aiBypass';

const CLASSIFICATION_TONE: Readonly<Record<InvestigationClassification, string>> = {
  DEFENDABLE: 'var(--status-good)',
  WEAK: 'var(--status-warning)',
  INDEFENSIBLE: 'var(--status-critical)',
  INSUFFICIENT_EVIDENCE: 'var(--status-serious)',
  AMBIGUOUS: 'var(--status-neutral)',
};

const CLASSIFICATION_MEANING: Readonly<Record<InvestigationClassification, string>> = {
  DEFENDABLE: 'The evidence set covers the network’s claim; a representment can be argued.',
  WEAK: 'Some of the claim is answered, but a mandatory link in the chain is thin.',
  INDEFENSIBLE: 'The evidence contradicts the merchant position or is absent where it matters.',
  INSUFFICIENT_EVIDENCE: 'Not enough material exists to reach any conclusion.',
  AMBIGUOUS: 'The evidence points both ways; a human has to decide.',
};

function isEvidenceType(value: string): value is EvidenceType {
  return (EVIDENCE_TYPES as readonly string[]).includes(value);
}

export interface AiReasoningTabProps {
  xray: CaseXRay;
  /** The platform's own admission record, when the gateway could supply it. */
  admission: AdmissionDecision | null;
  /** Applicable policy, for the confidence floor. Null while it loads or when absent. */
  policy: PolicyView | null;
  bypassedAi: boolean;
}

/**
 * The investigation, rendered honestly.
 *
 * The rule this screen enforces on itself: never present a model claim as a platform fact.
 * Every citation is shown as a claim *plus* the artifact it points at, and any citation whose
 * evidence id is not attached to this case is flagged as unsupported - which is exactly what
 * `AiResultValidator` does before it refuses the result (contract 9.3, rule 1).
 */
export function AiReasoningTab({ xray, admission, policy, bypassedAi }: AiReasoningTabProps) {
  const result = xray.investigation;
  const evidenceIds = React.useMemo(
    () => new Set(xray.evidence.map((item) => item.evidenceId)),
    [xray.evidence],
  );
  const bypass = React.useMemo(() => deriveBypass(xray), [xray]);

  const unsupportedClaimSet = React.useMemo(
    () => new Set(xray.safetyVerdict?.unsupportedClaims ?? []),
    [xray.safetyVerdict],
  );

  const citations = React.useMemo(() => {
    if (!result) return [];
    return result.citations.map((citation) => {
      const attached = evidenceIds.has(citation.evidenceId);
      const flaggedByGate = unsupportedClaimSet.has(citation.claim);
      return { citation, attached, flaggedByGate, unsupported: !attached || flaggedByGate };
    });
  }, [result, evidenceIds, unsupportedClaimSet]);

  const danglingSupport = React.useMemo(
    () => (result?.supportingEvidence ?? []).filter((id) => !evidenceIds.has(id)),
    [result, evidenceIds],
  );

  const unsupportedCount = citations.filter((item) => item.unsupported).length;

  /* ---------------------------------------------------------------- no model call at all */

  if (!result) {
    return (
      <div className="space-y-4">
        <BypassBanner
          title="This case never went to the model"
          shortCircuit={activeShortCircuit(admission?.shortCircuit) ?? bypass.shortCircuit}
          action={admission?.deterministicAction ?? bypass.deterministicAction}
          rule={bypass.rule}
          detail={admission?.reason ?? bypass.detail}
          reconstructed={admission === null}
        />
        {admission ? <AdmissionPanel admission={admission} /> : null}
        <p className="text-xs text-muted-foreground">
          Nothing on this tab is missing by accident. Most cases are resolved by{' '}
          <span className="mono-id">evidence-core</span> without a model call, and that is the
          platform working as designed: the reasoner is a scarce resource applied only to
          ambiguity (contract 9.4).
        </p>
      </div>
    );
  }

  const deterministic = bypassedAi;

  return (
    <div className="space-y-4">
      {deterministic ? (
        <BypassBanner
          title="Resolved on the deterministic path"
          shortCircuit={activeShortCircuit(admission?.shortCircuit) ?? bypass.shortCircuit}
          action={admission?.deterministicAction ?? result.recommendedAction}
          rule={bypass.rule}
          detail={admission?.reason ?? bypass.detail}
          reconstructed={admission === null}
        />
      ) : null}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="space-y-4">
          <section className="surface-card p-4" aria-label="Classification">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-sm font-semibold text-foreground">Classification</h2>
                <p
                  className="mt-1 text-2xl font-semibold leading-none"
                  style={{ color: CLASSIFICATION_TONE[result.classification] }}
                >
                  {humanizeEnum(result.classification)}
                </p>
                <p className="mt-1.5 max-w-md text-xs text-muted-foreground">
                  {CLASSIFICATION_MEANING[result.classification]}
                </p>
              </div>
              <div className="text-right">
                <p className="text-2xs uppercase tracking-wide text-muted-foreground">
                  Recommended action
                </p>
                <Badge variant="outline" className="mt-1">
                  {humanizeEnum(result.recommendedAction)}
                </Badge>
                <p className="mt-1 max-w-[14rem] text-2xs text-muted-foreground">
                  A recommendation only. The safety gate decides whether it is permitted.
                </p>
              </div>
            </div>
          </section>

          <section className="surface-card p-4" aria-label="Reasoning summary">
            <h2 className="text-sm font-semibold text-foreground">Reasoning summary</h2>
            <p className="mt-2 whitespace-pre-line text-sm leading-relaxed text-foreground">
              {result.reasoningSummary || 'The reasoner returned no summary.'}
            </p>
            {result.narrative ? (
              <>
                <h3 className="mt-4 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  Proposed representment narrative
                </h3>
                <p className="mt-1.5 whitespace-pre-line text-sm leading-relaxed text-muted-foreground">
                  {result.narrative}
                </p>
                <p className="mt-2 text-2xs text-muted-foreground">
                  This narrative is only written into a package after the gate allows it; until
                  then it is a draft with no effect on financial state.
                </p>
              </>
            ) : null}
          </section>

          <section className="surface-card p-4" aria-label="Citations">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
                <Quote className="size-4 text-muted-foreground" aria-hidden />
                Citations
              </h2>
              <span className="text-2xs text-muted-foreground">
                {citations.length} claim{citations.length === 1 ? '' : 's'} ·{' '}
                {unsupportedCount} unsupported
              </span>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              Every claim must name the artifact that proves it. A claim whose evidence id is not
              attached to this case is an invented claim, and the validator rejects the whole
              result over it (contract 9.3, rule 1; metric{' '}
              <span className="mono-id">pdei_ai_unsupported_claims_total</span>).
            </p>

            {citations.length === 0 ? (
              <p className="mt-3 rounded-md border border-dashed border-border p-4 text-center text-xs text-muted-foreground">
                The result carries no citations. An uncited conclusion is not usable as evidence
                and cannot be argued to a network.
              </p>
            ) : (
              <ul className="mt-3 space-y-2">
                {citations.map(({ citation, attached, flaggedByGate, unsupported }, index) => (
                  <li
                    key={`${citation.evidenceId}-${index}`}
                    className={cn(
                      'rounded-md border p-3',
                      unsupported
                        ? 'border-[color:var(--status-critical)]/45 bg-[color:var(--status-critical)]/8'
                        : 'border-border bg-card',
                    )}
                  >
                    <div className="flex items-start gap-2">
                      {unsupported ? (
                        <Link2Off
                          className="mt-0.5 size-4 shrink-0"
                          style={{ color: 'var(--status-critical)' }}
                          aria-hidden
                        />
                      ) : (
                        <BadgeCheck
                          className="mt-0.5 size-4 shrink-0"
                          style={{ color: 'var(--status-good)' }}
                          aria-hidden
                        />
                      )}
                      <div className="min-w-0 flex-1">
                        <p className="text-sm text-foreground">{citation.claim}</p>
                        <div className="mt-1.5 flex flex-wrap items-center gap-2 text-2xs">
                          <span className="text-muted-foreground">supported by</span>
                          {attached ? (
                            <Link
                              href={`/evidence/${citation.evidenceId}`}
                              className="mono-id text-primary underline-offset-4 hover:underline"
                            >
                              {citation.evidenceId}
                            </Link>
                          ) : (
                            <span className="mono-id text-foreground">{citation.evidenceId}</span>
                          )}
                          {!attached ? (
                            <Badge
                              variant="outline"
                              className="border-[color:var(--status-critical)]/45 text-[color:var(--status-critical)]"
                            >
                              not attached to this case
                            </Badge>
                          ) : null}
                          {flaggedByGate ? (
                            <Badge
                              variant="outline"
                              className="border-[color:var(--status-critical)]/45 text-[color:var(--status-critical)]"
                            >
                              flagged unsupported by the gate
                            </Badge>
                          ) : null}
                        </div>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {danglingSupport.length > 0 ? (
              <Alert variant="critical" className="mt-3">
                <ShieldOff aria-hidden />
                <AlertTitle>
                  {danglingSupport.length} supporting evidence id
                  {danglingSupport.length === 1 ? '' : 's'} cannot be resolved
                </AlertTitle>
                <AlertDescription>
                  <span className="mono-id">{danglingSupport.join(', ')}</span> appear in{' '}
                  <span className="mono-id">supportingEvidence</span> but are not attached to this
                  case. That is an automatic DENY under contract 9.3, rule 1.
                </AlertDescription>
              </Alert>
            ) : null}
          </section>

          {result.missingEvidence.length > 0 ? (
            <section className="surface-card p-4" aria-label="Missing evidence">
              <h2 className="text-sm font-semibold text-foreground">
                Evidence the reasoner says is missing
              </h2>
              <ul className="mt-2 flex flex-wrap gap-2">
                {result.missingEvidence.map((entry) => (
                  <li
                    key={entry}
                    className="inline-flex items-center gap-1.5 rounded-md border border-border px-2 py-1 text-xs"
                  >
                    {isEvidenceType(entry) ? (
                      <>
                        <EvidenceTypeIcon type={entry} />
                        {EVIDENCE_TYPE_LABEL[entry]}
                      </>
                    ) : (
                      <span className="mono-id">{entry}</span>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </div>

        <aside className="space-y-4">
          <section className="surface-card p-4" aria-label="Confidence">
            <h2 className="text-sm font-semibold text-foreground">Confidence</h2>
            <ConfidenceMeter
              className="mt-3"
              confidence={result.confidence}
              floor={policy?.autoPrepareMinConfidence ?? null}
              deterministic={deterministic}
            />
            {policy ? (
              <p className="mt-2 text-2xs text-muted-foreground">
                Floor from policy <span className="mono-id">{policy.policyVersionId}</span>{' '}
                (v{policy.version}).
              </p>
            ) : (
              <p className="mt-2 text-2xs text-muted-foreground">
                No policy loaded for this merchant and reason code, so the floor is not drawn.
              </p>
            )}
          </section>

          {admission ? <AdmissionPanel admission={admission} /> : null}

          <section className="surface-card p-4" aria-label="Model metadata">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
              <Cpu className="size-4 text-muted-foreground" aria-hidden />
              Provider
            </h2>
            <dl className="mt-2 space-y-1.5 text-xs">
              <MetaRow label="Provider" value={result.modelMetadata.provider} mono />
              <MetaRow label="Model" value={result.modelMetadata.model} mono />
              <MetaRow label="Attempt" value={String(result.modelMetadata.attempt)} />
              <MetaRow label="Latency" value={formatLatency(result.modelMetadata.latencyMs)} />
              <MetaRow
                label="Prompt tokens"
                value={result.modelMetadata.promptTokens.toLocaleString('en-US')}
              />
              <MetaRow
                label="Completion tokens"
                value={result.modelMetadata.completionTokens.toLocaleString('en-US')}
              />
            </dl>
            <p className="mt-2 text-2xs text-muted-foreground">
              Java never imports a provider SDK; this metadata reached the console through{' '}
              <span className="mono-id">ai-reasoning-service</span> and the gateway (contract 9.5).
            </p>
          </section>

          <section className="surface-card p-4" aria-label="Investigation identity">
            <h2 className="text-sm font-semibold text-foreground">Investigation</h2>
            <div className="mt-2 space-y-1.5 text-xs">
              <CopyableId id={result.investigationId} />
              <p className="text-muted-foreground">
                Stored in <span className="mono-id">pdei.investigations</span> with its own
                admission log row. Nothing here mutated financial state.
              </p>
            </div>
          </section>
        </aside>
      </div>
    </div>
  );
}

function AdmissionPanel({ admission }: { admission: AdmissionDecision }) {
  const terms: readonly { label: string; weight: number; value: number }[] = [
    { label: 'Financial impact', weight: 0.4, value: admission.financialImpact },
    { label: 'Deadline urgency', weight: 0.25, value: admission.deadlineUrgency },
    { label: 'Ambiguity', weight: 0.2, value: admission.ambiguityScore },
    {
      label: 'Deterministic uncertainty',
      weight: 0.15,
      value: 1 - admission.deterministicConfidence,
    },
  ];

  return (
    <section className="surface-card p-4" aria-label="Admission control">
      <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <SquareStack className="size-4 text-muted-foreground" aria-hidden />
        Admission control
      </h2>
      <div className="mt-2 flex items-baseline gap-2">
        <span className="text-2xl font-semibold leading-none text-foreground tabular">
          {Math.round(admission.priority)}
        </span>
        <span className="text-xs text-muted-foreground">priority / 100</span>
        <Badge variant={admission.admit ? 'primary' : 'subtle'} className="ml-auto">
          {admission.admit ? 'admitted' : 'not admitted'}
        </Badge>
      </div>
      <p className="mt-1.5 text-xs text-muted-foreground">{admission.reason}</p>

      <ul className="mt-3 space-y-1.5">
        {terms.map((term) => (
          <li key={term.label} className="text-2xs">
            <div className="flex items-center justify-between gap-2">
              <span className="text-muted-foreground">
                {term.label} <span className="tabular">×{term.weight}</span>
              </span>
              <span className="tabular text-foreground">
                {(term.value * term.weight * 100).toFixed(1)}
              </span>
            </div>
            <div className="mt-0.5 h-1 w-full overflow-hidden rounded-full bg-muted">
              <div
                className="h-full rounded-full"
                style={{
                  width: `${Math.min(100, Math.max(0, term.value * 100))}%`,
                  backgroundColor: 'var(--chart-1)',
                }}
              />
            </div>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-2xs text-muted-foreground">
        Contract 9.4: admit when priority ≥ 55, the Redis token bucket allows it, and the
        deterministic path is unresolved.
      </p>
      {admission.shortCircuit !== 'NONE' ? (
        <p className="mt-1.5 inline-flex items-center gap-1.5 text-2xs" style={{ color: 'var(--status-neutral)' }}>
          <CircleSlash className="size-3" aria-hidden />
          Short-circuit: <span className="mono-id">{admission.shortCircuit}</span>
        </p>
      ) : null}
    </section>
  );
}

function BypassBanner({
  title,
  shortCircuit,
  action,
  rule,
  detail,
  reconstructed,
}: {
  title: string;
  shortCircuit: string | null;
  action: string | null;
  rule: string;
  detail: string;
  reconstructed: boolean;
}) {
  return (
    <section
      className="rounded-lg border border-primary/35 bg-primary/5 p-4"
      aria-label="Deterministic short-circuit"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Zap className="size-4" aria-hidden />
        </span>
        <div className="min-w-0 space-y-1.5">
          <h2 className="text-sm font-semibold text-foreground">{title}</h2>
          <div className="flex flex-wrap items-center gap-2 text-xs">
            {shortCircuit ? (
              <Badge variant="primary" className="mono-id">
                {shortCircuit}
              </Badge>
            ) : (
              <Badge variant="subtle">admission control not reached</Badge>
            )}
            {action ? (
              <span className="text-muted-foreground">
                resolved to <span className="font-medium text-foreground">{humanizeEnum(action)}</span>
              </span>
            ) : null}
          </div>
          <p className="text-xs text-muted-foreground">{detail}</p>
          <p className="text-2xs text-muted-foreground">{rule}</p>
          {reconstructed ? (
            <p className="inline-flex items-center gap-1.5 text-2xs" style={{ color: 'var(--status-warning)' }}>
              <Bot className="size-3" aria-hidden />
              Reconstructed by the console from the stored case state — the gateway did not
              return an AdmissionDecision for this case.
            </p>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function MetaRow({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={cn('truncate text-foreground', mono ? 'mono-id' : 'tabular')}>{value}</dd>
    </div>
  );
}
