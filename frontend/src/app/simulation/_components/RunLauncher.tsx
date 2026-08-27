'use client';

import * as React from 'react';
import { Dices, Play } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ConfirmDialog } from '@/components/shared/ConfirmDialog';
import type { SimulationRunRequest } from '@/lib/types/simulation';
import { FAILURE_PROFILES } from './chaosCatalog';

const DEFAULTS = {
  seed: 20260826,
  merchants: 3,
  transactions: 500,
  days: 30,
  disputeRatePercent: 3,
  failureProfile: 'NONE',
} as const;

export interface RunLauncherProps {
  onStart: (request: SimulationRunRequest) => Promise<unknown>;
  busy: boolean;
}

/**
 * Launcher for a seeded workload run (`POST /sim/v1/runs`).
 *
 * The seed is the point: contract 17, rule 11 requires reproducible workloads, so the same seed
 * with the same parameters must produce the same events, the same evidence and the same
 * disputes. The dispute rate is entered as a percentage and sent as the [0,1] rate the API
 * expects - converted by multiplication, never by dividing by a hard-coded 100.
 */
export function RunLauncher({ onStart, busy }: RunLauncherProps) {
  const [seed, setSeed] = React.useState<number>(DEFAULTS.seed);
  const [merchants, setMerchants] = React.useState<number>(DEFAULTS.merchants);
  const [transactions, setTransactions] = React.useState<number>(DEFAULTS.transactions);
  const [days, setDays] = React.useState<number>(DEFAULTS.days);
  const [disputeRatePercent, setDisputeRatePercent] = React.useState<number>(
    DEFAULTS.disputeRatePercent,
  );
  const [failureProfile, setFailureProfile] = React.useState<string>(DEFAULTS.failureProfile);
  const [confirmOpen, setConfirmOpen] = React.useState(false);

  const profile = FAILURE_PROFILES.find((item) => item.value === failureProfile);
  const expectedDisputes = Math.round(transactions * disputeRatePercent * 0.01);

  const request: SimulationRunRequest = {
    seed,
    merchants,
    transactions,
    days,
    disputeRate: disputeRatePercent * 0.01,
    failureProfile,
    requestedBy: 'console',
  };

  return (
    <section className="surface-card p-4" aria-label="Run launcher">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-foreground">Launch a run</h2>
        <span className="text-2xs text-muted-foreground">
          POST <span className="mono-id">/sim/v1/runs</span>
        </span>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        Generates a merchant population, their transactions and the evidence trail behind them,
        then raises disputes against a share of it. The same seed always produces the same world.
      </p>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <div className="space-y-1.5">
          <Label htmlFor="sim-seed">Seed</Label>
          <div className="flex gap-2">
            <Input
              id="sim-seed"
              type="number"
              value={seed}
              min={0}
              onChange={(event) => setSeed(Math.max(0, Math.trunc(Number(event.target.value) || 0)))}
              className="tabular"
            />
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={() => setSeed(Math.floor(Math.random() * 100_000_000))}
              aria-label="Randomise seed"
              title="Randomise the seed"
            >
              <Dices className="size-4" />
            </Button>
          </div>
          <p className="text-2xs text-muted-foreground">
            Reproducible: rerun with this seed to regenerate the identical workload.
          </p>
        </div>

        <NumberField
          id="sim-merchants"
          label="Merchants"
          value={merchants}
          min={1}
          max={50}
          onChange={setMerchants}
          hint="Each gets its own currency, policy set and win-rate history."
        />

        <NumberField
          id="sim-transactions"
          label="Transactions"
          value={transactions}
          min={1}
          max={100_000}
          onChange={setTransactions}
          hint="Total across every merchant in the run."
        />

        <NumberField
          id="sim-days"
          label="Days of history"
          value={days}
          min={1}
          max={365}
          onChange={setDays}
          hint="Events are spread across this window, ending now."
        />

        <div className="space-y-1.5">
          <Label htmlFor="sim-dispute-rate">
            Dispute rate
            <span className="ml-2 tabular font-normal text-muted-foreground">
              {disputeRatePercent}%
            </span>
          </Label>
          <input
            id="sim-dispute-rate"
            type="range"
            min={0}
            max={100}
            step={1}
            value={disputeRatePercent}
            onChange={(event) => setDisputeRatePercent(Number(event.target.value))}
            className="w-full accent-[color:hsl(var(--primary))]"
          />
          <p className="text-2xs text-muted-foreground">
            About <span className="tabular">{expectedDisputes}</span> disputes, each opening a case
            workflow.
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="sim-failure-profile">Failure profile</Label>
          <select
            id="sim-failure-profile"
            value={failureProfile}
            onChange={(event) => setFailureProfile(event.target.value)}
            className="h-9 w-full rounded-md border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {FAILURE_PROFILES.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
          <p className="text-2xs text-muted-foreground">{profile?.detail}</p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-border pt-3">
        <Button size="sm" onClick={() => setConfirmOpen(true)} disabled={busy}>
          <Play className="size-3.5" />
          Start run
        </Button>
        <span className="text-2xs text-muted-foreground">
          Runs write real events onto <span className="mono-id">pdei.raw.events.v1</span>; every
          downstream worker will process them.
        </span>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Start a simulation run"
        description="The simulator publishes to the same topics production traffic uses. Existing data is not deleted; the run adds to it."
        confirmLabel="Start run"
        onConfirm={async () => {
          await onStart(request);
          setConfirmOpen(false);
        }}
      >
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs">
          <SummaryRow label="Seed" value={String(seed)} />
          <SummaryRow label="Merchants" value={String(merchants)} />
          <SummaryRow label="Transactions" value={String(transactions)} />
          <SummaryRow label="Days" value={String(days)} />
          <SummaryRow label="Dispute rate" value={`${disputeRatePercent}%`} />
          <SummaryRow label="Failure profile" value={failureProfile} />
        </dl>
      </ConfirmDialog>
    </section>
  );
}

function NumberField({
  id,
  label,
  value,
  min,
  max,
  onChange,
  hint,
}: {
  id: string;
  label: string;
  value: number;
  min: number;
  max: number;
  onChange: (value: number) => void;
  hint: string;
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="number"
        min={min}
        max={max}
        step={1}
        value={value}
        onChange={(event) =>
          onChange(Math.min(max, Math.max(min, Math.trunc(Number(event.target.value) || min))))
        }
        className="tabular"
      />
      <p className="text-2xs text-muted-foreground">{hint}</p>
    </div>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="tabular text-foreground">{value}</dd>
    </>
  );
}
