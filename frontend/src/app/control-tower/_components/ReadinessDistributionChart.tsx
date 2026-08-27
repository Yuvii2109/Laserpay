'use client';

import * as React from 'react';
import Link from 'next/link';
import { Bar, BarChart, Cell, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Table2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/shared';
import { BAND_DESCRIPTION, BAND_LABEL, bandColorVar } from '@/lib/format/score';
import { formatRatio } from '@/lib/format/money';
import { READINESS_BANDS, type ReadinessBand } from '@/lib/types/readiness';

export interface ReadinessDistributionChartProps {
  distribution: Record<ReadinessBand, number> | undefined;
  className?: string;
}

interface BandRow {
  band: ReadinessBand;
  label: string;
  count: number;
  share: number;
}

/**
 * How many transactions sit in each readiness band.
 *
 * It is a magnitude across four *ordinal* categories, so it is a bar chart: length answers
 * "how many" directly and the categories keep their contract 6 order (best to worst) rather
 * than being re-sorted by size. The fill is the reserved readiness band colour - these bands
 * are a state, not a series, and they always ship with their name and their count beside them,
 * so the reading survives colour-blindness and greyscale.
 *
 * One value axis, no second scale, no donut: a donut of four ordinal steps would ask the reader
 * to compare arc lengths for a comparison that is fundamentally "how far along the scale".
 */
export function ReadinessDistributionChart({
  distribution,
  className,
}: ReadinessDistributionChartProps) {
  const [showTable, setShowTable] = React.useState(false);

  const rows = React.useMemo<BandRow[]>(() => {
    const source = distribution ?? ({} as Record<ReadinessBand, number>);
    const total = READINESS_BANDS.reduce((sum, band) => sum + (source[band] ?? 0), 0);
    return READINESS_BANDS.map((band) => {
      const count = source[band] ?? 0;
      return {
        band,
        label: BAND_LABEL[band],
        count,
        share: total > 0 ? count / total : 0,
      };
    });
  }, [distribution]);

  const total = rows.reduce((sum, row) => sum + row.count, 0);

  if (total === 0) {
    return (
      <EmptyState
        title="No scored transactions"
        description="Readiness is computed by readiness-worker on every evidence event and on a nightly expiry sweep. Nothing in this merchant's window has been scored yet."
        className={className}
        compact
      />
    );
  }

  const maxCount = rows.reduce((max, row) => Math.max(max, row.count), 0);

  return (
    <div className={className}>
      <div className="w-full" style={{ height: rows.length * 46 + 8 }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={rows}
            layout="vertical"
            margin={{ top: 4, right: 64, bottom: 4, left: 0 }}
            barCategoryGap={12}
          >
            {/* Direct labels carry every value, so the value axis is redundant chrome. */}
            <XAxis type="number" hide domain={[0, Math.max(1, maxCount)]} />
            <YAxis
              type="category"
              dataKey="label"
              width={104}
              axisLine={false}
              tickLine={false}
              tick={{ fontSize: 12 }}
            />
            <Tooltip
              cursor={{ fill: 'hsl(var(--accent))', fillOpacity: 0.5 }}
              content={<DistributionTooltip />}
            />
            <Bar dataKey="count" barSize={18} radius={[0, 4, 4, 0]} isAnimationActive={false}>
              {rows.map((row) => (
                <Cell key={row.band} fill={bandColorVar(row.band)} />
              ))}
              <LabelList
                dataKey="count"
                position="right"
                offset={10}
                fontSize={12}
                fontWeight={600}
                style={{ fill: 'var(--viz-ink)' }}
              />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">
          {total} scored transaction{total === 1 ? '' : 's'} ·{' '}
          {formatRatio((rows[2]?.count ?? 0) / total + (rows[3]?.count ?? 0) / total)} at risk or
          not ready
        </p>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setShowTable((current) => !current)}
          aria-expanded={showTable}
        >
          <Table2 className="size-3.5" aria-hidden />
          {showTable ? 'Hide table' : 'Show table'}
        </Button>
      </div>

      {showTable ? (
        <div className="mt-2 overflow-x-auto rounded-lg border border-border scrollbar-thin">
          <table className="w-full text-sm">
            <caption className="sr-only">
              Transactions per readiness band, with each band&apos;s share of the total.
            </caption>
            <thead>
              <tr className="border-b border-border text-xs text-muted-foreground">
                <th scope="col" className="px-3 py-1.5 text-left font-medium">Band</th>
                <th scope="col" className="px-3 py-1.5 text-left font-medium">Score range</th>
                <th scope="col" className="px-3 py-1.5 text-right font-medium">Transactions</th>
                <th scope="col" className="px-3 py-1.5 text-right font-medium">Share</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.band} className="border-b border-border last:border-0">
                  <th scope="row" className="px-3 py-1.5 text-left font-normal">
                    <span className="inline-flex items-center gap-2">
                      <span
                        className="size-2.5 rounded-[2px]"
                        style={{ backgroundColor: bandColorVar(row.band) }}
                        aria-hidden
                      />
                      <Link
                        href={`/transactions?band=${row.band}`}
                        className="underline-offset-4 hover:underline"
                      >
                        {row.label}
                      </Link>
                    </span>
                  </th>
                  <td className="px-3 py-1.5 text-xs text-muted-foreground">
                    {SCORE_RANGE[row.band]}
                  </td>
                  <td className="tabular px-3 py-1.5 text-right">{row.count}</td>
                  <td className="tabular px-3 py-1.5 text-right text-muted-foreground">
                    {formatRatio(row.share)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}

const SCORE_RANGE: Readonly<Record<ReadinessBand, string>> = {
  READY: '90 – 100',
  NEARLY_READY: '75 – 89',
  AT_RISK: '50 – 74',
  NOT_READY: '0 – 49',
};

function DistributionTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: { payload?: BandRow }[];
}) {
  const row = active ? payload?.[0]?.payload : undefined;
  if (!row) return null;
  return (
    <div className="max-w-xs rounded-md border border-border bg-popover p-2.5 text-xs shadow-lg">
      <p className="flex items-center gap-2 font-medium text-foreground">
        <span
          className="size-2.5 rounded-[2px]"
          style={{ backgroundColor: bandColorVar(row.band) }}
          aria-hidden
        />
        {row.label}
      </p>
      <p className="mt-1 tabular text-foreground">
        {row.count} transaction{row.count === 1 ? '' : 's'} · {formatRatio(row.share)}
      </p>
      <p className="mt-1 text-muted-foreground">{BAND_DESCRIPTION[row.band]}</p>
    </div>
  );
}
