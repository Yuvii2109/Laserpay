'use client';

import * as React from 'react';
import { Gauge, ShieldAlert } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { MoneyDisplay } from '@/components/shared/MoneyDisplay';
import { formatConfidence } from '@/lib/format/score';
import { formatMoney, parseMoneyInput } from '@/lib/format/money';
import type { PolicyDraft, PolicyView } from '@/lib/types/policy';
import { policyLabel } from './policyDraft';

export interface ThresholdsPanelProps {
  policy: PolicyView;
  draft: PolicyDraft;
  onChange: (next: PolicyDraft) => void;
}

/**
 * The automation thresholds of one policy version.
 *
 * These four numbers are what actually decide whether a case is allowed to proceed without a
 * person, so each one is shown next to the rule it feeds (contract 9.3 rules 4 and 5, and the
 * auto-prepare short-circuit of 9.4). The money threshold is edited in major units and stored
 * in minor units - the input never becomes a float.
 */
export function ThresholdsPanel({ policy, draft, onChange }: ThresholdsPanelProps) {
  const [amountInput, setAmountInput] = React.useState(() =>
    formatMoney(
      { amountMinor: draft.humanReviewAboveAmountMinor, currency: draft.currency },
      { display: 'none' },
    ).replace(/,/g, ''),
  );
  const [amountError, setAmountError] = React.useState<string | null>(null);

  // Reset the local text buffer whenever a different policy is selected.
  React.useEffect(() => {
    setAmountInput(
      formatMoney(
        { amountMinor: draft.humanReviewAboveAmountMinor, currency: draft.currency },
        { display: 'none' },
      ).replace(/,/g, ''),
    );
    setAmountError(null);
    // Only on policy identity change; typing must not fight the buffer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policy.policyId]);

  const patch = (partial: Partial<PolicyDraft>) => onChange({ ...draft, ...partial });

  const onAmountChange = (value: string) => {
    setAmountInput(value);
    const parsed = parseMoneyInput(value, draft.currency);
    if (!parsed) {
      setAmountError('Enter an amount in major units, e.g. 50000 or 50000.00');
      return;
    }
    setAmountError(null);
    patch({ humanReviewAboveAmountMinor: parsed.amountMinor });
  };

  return (
    <section className="surface-card p-4" aria-label="Automation thresholds">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Gauge className="size-4 text-muted-foreground" aria-hidden />
          Automation thresholds
        </h2>
        <span className="text-2xs text-muted-foreground">
          {policyLabel(policy)} · <span className="mono-id">{policy.policyVersionId}</span>
        </span>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        These are the numbers the safety gate compares against. Changing one and publishing
        creates a new immutable version; cases already gated keep the version they were gated on.
      </p>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="autoPrepareMinConfidence">
            autoPrepareMinConfidence
            <span className="ml-2 tabular font-normal text-muted-foreground">
              {formatConfidence(draft.autoPrepareMinConfidence)}
            </span>
          </Label>
          <input
            id="autoPrepareMinConfidence"
            type="range"
            min={0}
            max={100}
            step={1}
            value={Math.round(draft.autoPrepareMinConfidence * 100)}
            onChange={(event) =>
              patch({ autoPrepareMinConfidence: Number(event.target.value) * 0.01 })
            }
            className="w-full accent-[color:hsl(var(--primary))]"
            aria-describedby="autoPrepareMinConfidence-help"
          />
          <p id="autoPrepareMinConfidence-help" className="text-2xs text-muted-foreground">
            Contract 9.3, rule 4: a PREPARE_REPRESENTMENT below this confidence is rejected and
            the case is routed to a human.
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="maxContradictions">maxContradictions</Label>
          <Input
            id="maxContradictions"
            type="number"
            min={0}
            max={20}
            step={1}
            value={draft.maxContradictions}
            onChange={(event) =>
              patch({ maxContradictions: Math.max(0, Math.trunc(Number(event.target.value) || 0)) })
            }
            aria-describedby="maxContradictions-help"
          />
          <p id="maxContradictions-help" className="text-2xs text-muted-foreground">
            Contract 9.3, rule 5. Zero is the platform default: a representment built on
            self-contradicting evidence is never filed automatically.
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="minReadinessScoreForAutoPrepare">
            minReadinessScoreForAutoPrepare
            <span className="ml-2 tabular font-normal text-muted-foreground">
              {draft.minReadinessScoreForAutoPrepare}/100
            </span>
          </Label>
          <input
            id="minReadinessScoreForAutoPrepare"
            type="range"
            min={0}
            max={100}
            step={1}
            value={draft.minReadinessScoreForAutoPrepare}
            onChange={(event) =>
              patch({ minReadinessScoreForAutoPrepare: Number(event.target.value) })
            }
            className="w-full accent-[color:hsl(var(--primary))]"
            aria-describedby="minReadiness-help"
          />
          <p id="minReadiness-help" className="text-2xs text-muted-foreground">
            The contract 7 readiness score a transaction must reach before the deterministic
            auto-prepare path is available.
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="humanReviewAboveAmount">
            humanReviewAboveAmountMinor
            <span className="ml-2 font-normal text-muted-foreground">({draft.currency})</span>
          </Label>
          <Input
            id="humanReviewAboveAmount"
            inputMode="decimal"
            value={amountInput}
            onChange={(event) => onAmountChange(event.target.value)}
            aria-describedby="humanReviewAboveAmount-help"
            aria-invalid={amountError !== null}
          />
          <p id="humanReviewAboveAmount-help" className="text-2xs text-muted-foreground">
            Above{' '}
            <MoneyDisplay
              money={{
                amountMinor: draft.humanReviewAboveAmountMinor,
                currency: draft.currency,
              }}
              withCode
              className="text-2xs"
            />{' '}
            a person always reviews, whatever the model said. Stored as{' '}
            <span className="tabular">{draft.humanReviewAboveAmountMinor}</span> minor units.
          </p>
          {amountError ? (
            <p className="text-2xs" style={{ color: 'var(--status-critical)' }}>
              {amountError}
            </p>
          ) : null}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="responseWindowDays">responseWindowDays</Label>
          <Input
            id="responseWindowDays"
            type="number"
            min={1}
            max={120}
            step={1}
            value={draft.responseWindowDays}
            onChange={(event) =>
              patch({ responseWindowDays: Math.max(1, Math.trunc(Number(event.target.value) || 1)) })
            }
          />
          <p className="text-2xs text-muted-foreground">
            Days the network allows for a representment; drives the case deadline.
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="expiringSoonDays">expiringSoonDays</Label>
          <Input
            id="expiringSoonDays"
            type="number"
            min={1}
            max={90}
            step={1}
            value={draft.expiringSoonDays}
            onChange={(event) =>
              patch({ expiringSoonDays: Math.max(1, Math.trunc(Number(event.target.value) || 1)) })
            }
          />
          <p className="text-2xs text-muted-foreground">
            Horizon for the EXPIRING_SOON gap. Contract 7 penalises mandatory evidence expiring
            inside 7 days by 5 points.
          </p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-border pt-3">
        <label className="inline-flex cursor-pointer items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.autoSubmitEnabled}
            onChange={(event) => patch({ autoSubmitEnabled: event.target.checked })}
            className="size-4 accent-[color:hsl(var(--primary))]"
          />
          autoSubmitEnabled
        </label>
        {draft.autoSubmitEnabled ? (
          <span
            className="inline-flex items-center gap-1.5 text-2xs"
            style={{ color: 'var(--status-warning)' }}
          >
            <ShieldAlert className="size-3.5" aria-hidden />
            With auto-submit on, an ALLOW verdict files the representment with no human step.
          </span>
        ) : (
          <span className="text-2xs text-muted-foreground">
            Off: every submission passes through the humanDecision signal.
          </span>
        )}
        <Badge variant="subtle" className="ml-auto text-2xs">
          {draft.permittedActions.length} permitted actions
        </Badge>
      </div>
    </section>
  );
}
