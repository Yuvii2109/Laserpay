'use client';

import * as React from 'react';
import { cn } from '@/lib/utils';
import { FUNNEL_STAGES } from '@/lib/types/metrics';
import type { FunnelMetrics } from '@/lib/types/metrics';

const VIEW_WIDTH = 800;
const LABEL_WIDTH = 190;
const VALUE_WIDTH = 120;
const FUNNEL_LEFT = LABEL_WIDTH + 16;
const FUNNEL_RIGHT = VIEW_WIDTH - VALUE_WIDTH - 16;
const FUNNEL_CENTER = (FUNNEL_LEFT + FUNNEL_RIGHT) / 2;
const FULL_WIDTH = FUNNEL_RIGHT - FUNNEL_LEFT;
const MIN_WIDTH = 26;
const ROW_HEIGHT = 44;
const ROW_GAP = 26;

/**
 * Stage colour: one hue, light to dark, interpolated between the two ends of the sequential
 * ramp. The funnel is ordinal, so the encoding is a single-hue ramp - never categorical slots,
 * and never a rainbow.
 */
function stageColor(index: number, total: number): string {
  const t = total <= 1 ? 0 : index / (total - 1);
  return `color-mix(in oklab, var(--seq-700) ${Math.round(t * 100)}%, var(--seq-250))`;
}

function percent(part: number, whole: number): string {
  if (whole <= 0) return '-';
  return `${((part / whole) * 100).toFixed(part / whole < 0.1 ? 1 : 0)}%`;
}

export interface FunnelChartProps {
  metrics: FunnelMetrics;
  className?: string;
}

/**
 * The events → candidates → ambiguous → AI → human funnel (`GET /metrics/funnel`).
 *
 * Drawn as an actual funnel because the reader's question is "how much falls out at each step",
 * and a narrowing shape answers that directly. Width encodes the count relative to the top
 * stage; every bar also carries its absolute number and its share, so nothing depends on
 * comparing areas by eye. The AI stage is annotated because it is the one that costs money.
 */
export function FunnelChart({ metrics, className }: FunnelChartProps) {
  const stages = React.useMemo(
    () =>
      FUNNEL_STAGES.map((stage) => ({
        key: stage.key,
        label: stage.label,
        value: Number(metrics[stage.key] ?? 0),
      })),
    [metrics],
  );

  const top = stages[0]?.value ?? 0;
  const height = stages.length * ROW_HEIGHT + Math.max(0, stages.length - 1) * ROW_GAP + 8;

  const widths = stages.map((stage) =>
    top <= 0 ? MIN_WIDTH : Math.max(MIN_WIDTH, (stage.value / top) * FULL_WIDTH),
  );

  return (
    <div className={cn('w-full overflow-x-auto scrollbar-thin', className)}>
      <svg
        role="img"
        aria-label={`Funnel: ${stages.map((stage) => `${stage.label} ${stage.value}`).join(', ')}`}
        viewBox={`0 0 ${VIEW_WIDTH} ${height}`}
        width="100%"
        style={{ minWidth: 520 }}
      >
        {stages.map((stage, index) => {
          const y = index * (ROW_HEIGHT + ROW_GAP);
          const width = widths[index] ?? MIN_WIDTH;
          const x = FUNNEL_CENTER - width / 2;
          const previous = index === 0 ? null : stages[index - 1];
          const nextWidth = widths[index + 1];
          const fill = stageColor(index, stages.length);

          return (
            <g key={stage.key}>
              {nextWidth !== undefined ? (
                <polygon
                  points={[
                    `${x},${y + ROW_HEIGHT}`,
                    `${x + width},${y + ROW_HEIGHT}`,
                    `${FUNNEL_CENTER + nextWidth / 2},${y + ROW_HEIGHT + ROW_GAP}`,
                    `${FUNNEL_CENTER - nextWidth / 2},${y + ROW_HEIGHT + ROW_GAP}`,
                  ].join(' ')}
                  fill={fill}
                  opacity={0.22}
                />
              ) : null}

              <rect x={x} y={y} width={width} height={ROW_HEIGHT} rx={5} fill={fill} />

              <text
                x={LABEL_WIDTH}
                y={y + ROW_HEIGHT / 2 - 2}
                textAnchor="end"
                fontSize={13}
                fontWeight={500}
                fill="var(--viz-ink)"
              >
                {stage.label}
              </text>
              <text
                x={LABEL_WIDTH}
                y={y + ROW_HEIGHT / 2 + 13}
                textAnchor="end"
                fontSize={10.5}
                fill="var(--viz-ink-muted)"
              >
                {previous
                  ? `${percent(stage.value, previous.value)} of ${previous.label.toLowerCase()}`
                  : 'everything ingested'}
              </text>

              <text
                x={FUNNEL_RIGHT + 16}
                y={y + ROW_HEIGHT / 2 - 2}
                fontSize={14}
                fontWeight={600}
                fill="var(--viz-ink)"
                style={{ fontVariantNumeric: 'tabular-nums' }}
              >
                {stage.value.toLocaleString('en-US')}
              </text>
              <text
                x={FUNNEL_RIGHT + 16}
                y={y + ROW_HEIGHT / 2 + 13}
                fontSize={10.5}
                fill="var(--viz-ink-muted)"
                style={{ fontVariantNumeric: 'tabular-nums' }}
              >
                {percent(stage.value, top)} of events
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
