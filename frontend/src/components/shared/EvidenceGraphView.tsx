'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { Network, Table2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { EmptyState } from './EmptyState';
import { CopyableId } from './CopyableId';
import { TimestampDisplay } from './TimestampDisplay';
import { hrefForId, humanizeEnum } from '@/lib/format/id';
import type { AggregateType } from '@/lib/types/events';
import type { EvidenceEdge, EvidenceGraph, EvidenceNode } from '@/lib/types/evidence';

export interface EvidenceGraphViewProps {
  graph: EvidenceGraph | null | undefined;
  className?: string;
  /** Node ids to highlight, e.g. the artifacts a citation or a gap points at. */
  highlightedIds?: readonly string[];
}

/* ------------------------------------------------------------------ geometry */

const NODE_WIDTH = 176;
const NODE_HEIGHT = 48;
const COLUMN_GAP = 72;
const ROW_GAP = 20;
const PADDING = 16;
const COLUMN_PITCH = NODE_WIDTH + COLUMN_GAP;
const ROW_PITCH = NODE_HEIGHT + ROW_GAP;

/**
 * Aggregate type -> accent. Assigned in fixed order and never cycled: the colour follows the
 * entity, so filtering the graph can never repaint a node that survived.
 */
const NODE_ACCENT: Readonly<Record<AggregateType, string>> = {
  TRANSACTION: 'var(--chart-1)',
  PAYMENT: 'var(--chart-2)',
  ORDER: 'var(--chart-3)',
  SHIPMENT: 'var(--chart-4)',
  DELIVERY: 'var(--chart-6)',
  EVIDENCE: 'var(--chart-7)',
  REFUND: 'var(--chart-8)',
  COMMUNICATION: 'var(--chart-5)',
  MERCHANT: 'var(--status-neutral)',
  CUSTOMER: 'var(--status-neutral)',
  POLICY: 'var(--status-neutral)',
  DISPUTE: 'var(--status-neutral)',
  CASE: 'var(--status-neutral)',
};

/** Relations that carry a conflict rather than structure; drawn as same-layer arcs. */
const CONFLICT_RELATIONS = new Set(['CONTRADICTS']);

interface PlacedNode {
  node: EvidenceNode;
  layer: number;
  order: number;
  x: number;
  y: number;
}

interface Layout {
  nodes: PlacedNode[];
  byId: Map<string, PlacedNode>;
  width: number;
  height: number;
  layerCount: number;
}

/**
 * Layered ("Sugiyama"-style) layout, hand-rolled - no graph library.
 *
 *   1. longest-path layer assignment over the structural edges only, so every edge points
 *      strictly rightwards and evidence lands upstream of the entity it evidences;
 *   2. barycentre ordering inside each layer (two forward sweeps, one backward) to pull
 *      connected nodes level with each other and cut crossings;
 *   3. fixed pitch placement.
 *
 * Cycles cannot hang it: the relaxation is bounded by the node count, and anything still
 * unresolved keeps the layer it reached.
 */
function layoutGraph(graph: EvidenceGraph): Layout {
  const nodes = graph.nodes ?? [];
  const known = new Set(nodes.map((node) => node.id));
  const structural = (graph.edges ?? []).filter(
    (edge) =>
      known.has(edge.from) &&
      known.has(edge.to) &&
      edge.from !== edge.to &&
      !CONFLICT_RELATIONS.has(String(edge.relation)),
  );

  /* ---- 1. longest-path layering ---- */
  const layer = new Map<string, number>();
  for (const node of nodes) layer.set(node.id, 0);
  for (let pass = 0; pass < nodes.length; pass += 1) {
    let moved = false;
    for (const edge of structural) {
      const source = layer.get(edge.from) ?? 0;
      const target = layer.get(edge.to) ?? 0;
      if (target < source + 1) {
        layer.set(edge.to, source + 1);
        moved = true;
      }
    }
    if (!moved) break;
  }

  const layerCount = nodes.reduce((max, node) => Math.max(max, layer.get(node.id) ?? 0), 0) + 1;
  const layers: EvidenceNode[][] = Array.from({ length: layerCount }, () => []);
  nodes.forEach((node) => {
    const index = Math.min(layer.get(node.id) ?? 0, layerCount - 1);
    layers[index]?.push(node);
  });

  /* ---- 2. barycentre ordering ---- */
  const order = new Map<string, number>();
  layers.forEach((column) => column.forEach((node, index) => order.set(node.id, index)));

  const neighboursIn = new Map<string, string[]>();
  const neighboursOut = new Map<string, string[]>();
  for (const edge of structural) {
    if (!neighboursIn.has(edge.to)) neighboursIn.set(edge.to, []);
    if (!neighboursOut.has(edge.from)) neighboursOut.set(edge.from, []);
    neighboursIn.get(edge.to)?.push(edge.from);
    neighboursOut.get(edge.from)?.push(edge.to);
  }

  const barycentre = (nodeId: string, side: 'in' | 'out'): number | null => {
    const list = (side === 'in' ? neighboursIn : neighboursOut).get(nodeId) ?? [];
    if (list.length === 0) return null;
    const total = list.reduce((sum, other) => sum + (order.get(other) ?? 0), 0);
    return total / list.length;
  };

  const sweep = (side: 'in' | 'out') => {
    const indices =
      side === 'in'
        ? layers.map((_, index) => index)
        : layers.map((_, index) => layers.length - 1 - index);
    for (const index of indices) {
      const column = layers[index];
      if (!column) continue;
      const keyed = column.map((node, position) => ({
        node,
        key: barycentre(node.id, side) ?? position,
        position,
      }));
      keyed.sort((a, b) => a.key - b.key || a.position - b.position);
      layers[index] = keyed.map((item) => item.node);
      layers[index]?.forEach((node, position) => order.set(node.id, position));
    }
  };

  sweep('in');
  sweep('out');
  sweep('in');

  /* ---- 3. placement ---- */
  const tallest = layers.reduce((max, column) => Math.max(max, column.length), 0);
  const placed: PlacedNode[] = [];
  layers.forEach((column, layerIndex) => {
    // Centre short columns against the tallest one so the picture is not top-heavy.
    const offset = ((tallest - column.length) * ROW_PITCH) / 2;
    column.forEach((node, index) => {
      placed.push({
        node,
        layer: layerIndex,
        order: index,
        x: PADDING + layerIndex * COLUMN_PITCH,
        y: PADDING + offset + index * ROW_PITCH,
      });
    });
  });

  return {
    nodes: placed,
    byId: new Map(placed.map((item) => [item.node.id, item])),
    width: PADDING * 2 + Math.max(1, layerCount) * NODE_WIDTH + Math.max(0, layerCount - 1) * COLUMN_GAP,
    height: PADDING * 2 + Math.max(1, tallest) * NODE_HEIGHT + Math.max(0, tallest - 1) * ROW_GAP,
    layerCount,
  };
}

/** Cubic bezier from the right edge of `from` to the left edge of `to`. */
function structuralPath(from: PlacedNode, to: PlacedNode): string {
  const x1 = from.x + NODE_WIDTH;
  const y1 = from.y + NODE_HEIGHT / 2;
  const x2 = to.x;
  const y2 = to.y + NODE_HEIGHT / 2;
  const midpoint = x1 + (x2 - x1) / 2;
  return `M ${x1} ${y1} C ${midpoint} ${y1} ${midpoint} ${y2} ${x2} ${y2}`;
}

/** Conflict edges usually join two nodes in the same column; bow them out to the left. */
function conflictPath(from: PlacedNode, to: PlacedNode): string {
  const x1 = from.x;
  const y1 = from.y + NODE_HEIGHT / 2;
  const x2 = to.x;
  const y2 = to.y + NODE_HEIGHT / 2;
  const bow = Math.max(28, Math.abs(y2 - y1) / 2);
  return `M ${x1} ${y1} C ${x1 - bow} ${y1} ${x2 - bow} ${y2} ${x2} ${y2}`;
}

/* ------------------------------------------------------------------ component */

export function EvidenceGraphView({ graph, className, highlightedIds }: EvidenceGraphViewProps) {
  const router = useRouter();
  const [activeId, setActiveId] = React.useState<string | null>(null);
  const [showTable, setShowTable] = React.useState(false);
  const highlighted = React.useMemo(() => new Set(highlightedIds ?? []), [highlightedIds]);

  const layout = React.useMemo(() => (graph ? layoutGraph(graph) : null), [graph]);

  if (!graph || !layout || layout.nodes.length === 0) {
    return (
      <EmptyState
        icon={Network}
        title="No evidence graph"
        description="The state builder has not linked any entities to this transaction yet. The graph is built from evidence relationships in pdei.evidence_relationships."
        className={className}
      />
    );
  }

  const typesPresent = Array.from(new Set(graph.nodes.map((node) => node.type)));
  const active = activeId ? layout.byId.get(activeId)?.node ?? null : null;
  const activeEdges = activeId
    ? graph.edges.filter((edge) => edge.from === activeId || edge.to === activeId)
    : [];

  const openNode = (node: EvidenceNode) => {
    const href = hrefForId(node.id);
    if (href) router.push(href);
  };

  return (
    <div className={cn('space-y-3', className)}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <ul className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
          {typesPresent.map((type) => (
            <li key={type} className="inline-flex items-center gap-1.5">
              <span
                className="size-2.5 rounded-[2px]"
                style={{ backgroundColor: NODE_ACCENT[type] ?? 'var(--status-neutral)' }}
                aria-hidden
              />
              {humanizeEnum(type)}
            </li>
          ))}
          <li className="inline-flex items-center gap-1.5">
            <span
              className="h-px w-4 border-t-2 border-dashed"
              style={{ borderColor: 'var(--status-critical)' }}
              aria-hidden
            />
            Contradicts
          </li>
        </ul>

        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setShowTable((current) => !current)}
          aria-expanded={showTable}
        >
          <Table2 className="size-3.5" aria-hidden />
          {showTable ? 'Hide table' : 'Show as table'}
        </Button>
      </div>

      <div className="overflow-x-auto rounded-lg border border-border bg-card scrollbar-thin">
        <svg
          width={layout.width}
          height={layout.height}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          role="img"
          aria-label={`Evidence graph for ${graph.rootId}: ${graph.nodes.length} nodes and ${graph.edges.length} relationships. A table view is available.`}
          className="block"
        >
          <defs>
            <marker
              id="pdei-graph-arrow"
              viewBox="0 0 8 8"
              refX="7"
              refY="4"
              markerWidth="7"
              markerHeight="7"
              orient="auto-start-reverse"
            >
              <path d="M 0 1 L 7 4 L 0 7 z" fill="var(--viz-axis)" />
            </marker>
            <marker
              id="pdei-graph-arrow-conflict"
              viewBox="0 0 8 8"
              refX="7"
              refY="4"
              markerWidth="7"
              markerHeight="7"
              orient="auto-start-reverse"
            >
              <path d="M 0 1 L 7 4 L 0 7 z" fill="var(--status-critical)" />
            </marker>
          </defs>

          <g>
            {graph.edges.map((edge, index) => {
              const from = layout.byId.get(edge.from);
              const to = layout.byId.get(edge.to);
              if (!from || !to) return null;
              const conflict = CONFLICT_RELATIONS.has(String(edge.relation));
              const touched = activeId === edge.from || activeId === edge.to;
              return (
                <path
                  key={`${edge.from}->${edge.to}:${edge.relation}:${index}`}
                  d={conflict ? conflictPath(from, to) : structuralPath(from, to)}
                  fill="none"
                  stroke={conflict ? 'var(--status-critical)' : 'var(--viz-axis)'}
                  strokeWidth={touched ? 2 : conflict ? 1.5 : 1}
                  strokeDasharray={conflict ? '4 3' : undefined}
                  markerEnd={`url(#${conflict ? 'pdei-graph-arrow-conflict' : 'pdei-graph-arrow'})`}
                  opacity={activeId && !touched ? 0.35 : 1}
                >
                  <title>{`${edge.from} -${String(edge.relation)}→ ${edge.to}`}</title>
                </path>
              );
            })}
          </g>

          <g>
            {layout.nodes.map((placed) => (
              <GraphNode
                key={placed.node.id}
                placed={placed}
                isRoot={placed.node.id === graph.rootId}
                isActive={activeId === placed.node.id}
                isHighlighted={highlighted.has(placed.node.id)}
                dimmed={Boolean(activeId) && activeId !== placed.node.id}
                onActivate={() => setActiveId(placed.node.id)}
                onOpen={() => openNode(placed.node)}
              />
            ))}
          </g>
        </svg>
      </div>

      <GraphInspector node={active} edges={activeEdges} />

      {showTable ? <GraphTable graph={graph} /> : null}
    </div>
  );
}

function GraphNode({
  placed,
  isRoot,
  isActive,
  isHighlighted,
  dimmed,
  onActivate,
  onOpen,
}: {
  placed: PlacedNode;
  isRoot: boolean;
  isActive: boolean;
  isHighlighted: boolean;
  dimmed: boolean;
  onActivate: () => void;
  onOpen: () => void;
}) {
  const { node, x, y } = placed;
  const accent = NODE_ACCENT[node.type] ?? 'var(--status-neutral)';
  const linkable = hrefForId(node.id) !== null;
  const label = `${humanizeEnum(node.type)} ${node.id}${node.status ? `, ${humanizeEnum(node.status)}` : ''}`;

  return (
    <g
      transform={`translate(${x} ${y})`}
      tabIndex={0}
      role={linkable ? 'link' : 'group'}
      aria-label={linkable ? `${label}. Open detail page.` : label}
      opacity={dimmed ? 0.5 : 1}
      style={{ cursor: linkable ? 'pointer' : 'default' }}
      onMouseEnter={onActivate}
      onFocus={onActivate}
      onClick={onOpen}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen();
        }
      }}
    >
      <rect
        width={NODE_WIDTH}
        height={NODE_HEIGHT}
        rx={8}
        fill="hsl(var(--card))"
        stroke={isActive || isHighlighted ? accent : 'hsl(var(--border))'}
        strokeWidth={isActive || isHighlighted ? 2 : 1}
      />
      <rect width={4} height={NODE_HEIGHT} rx={2} fill={accent} />
      {isRoot ? (
        <rect
          x={-3}
          y={-3}
          width={NODE_WIDTH + 6}
          height={NODE_HEIGHT + 6}
          rx={10}
          fill="none"
          stroke={accent}
          strokeWidth={1}
          strokeDasharray="3 3"
          opacity={0.6}
        />
      ) : null}
      <text x={14} y={19} fontSize={12} fontWeight={600} fill="var(--viz-ink)">
        {truncate(node.label || node.id, 22)}
      </text>
      <text x={14} y={35} fontSize={10.5} fill="var(--viz-ink-muted)">
        {truncate(node.status ? `${node.id} · ${humanizeEnum(node.status)}` : node.id, 26)}
      </text>
      <title>{label}</title>
    </g>
  );
}

function GraphInspector({ node, edges }: { node: EvidenceNode | null; edges: EvidenceEdge[] }) {
  if (!node) {
    return (
      <p className="text-xs text-muted-foreground">
        Hover or focus a node to inspect it. Nodes with a detail page open on Enter.
      </p>
    );
  }
  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-sm">
        <span
          className="size-2.5 rounded-[2px]"
          style={{ backgroundColor: NODE_ACCENT[node.type] ?? 'var(--status-neutral)' }}
          aria-hidden
        />
        <span className="font-medium text-foreground">{node.label || node.id}</span>
        <CopyableId id={node.id} className="text-xs" />
        <span className="text-xs text-muted-foreground">{humanizeEnum(node.type)}</span>
        {node.status ? (
          <span className="text-xs text-muted-foreground">{humanizeEnum(node.status)}</span>
        ) : null}
        {node.at ? (
          <span className="text-xs text-muted-foreground">
            <TimestampDisplay value={node.at} />
          </span>
        ) : null}
      </div>
      {edges.length > 0 ? (
        <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
          {edges.map((edge, index) => (
            <li key={`${edge.from}-${edge.to}-${index}`} className="mono-id">
              {edge.from === node.id ? `→ ${String(edge.relation)} ${edge.to}` : `← ${String(edge.relation)} ${edge.from}`}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function GraphTable({ graph }: { graph: EvidenceGraph }) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <div className="overflow-x-auto rounded-lg border border-border bg-card scrollbar-thin">
        <table className="w-full text-sm">
          <caption className="border-b border-border px-3 py-2 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Nodes ({graph.nodes.length})
          </caption>
          <thead>
            <tr className="border-b border-border text-xs text-muted-foreground">
              <th scope="col" className="px-3 py-1.5 text-left font-medium">Id</th>
              <th scope="col" className="px-3 py-1.5 text-left font-medium">Type</th>
              <th scope="col" className="px-3 py-1.5 text-left font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {graph.nodes.map((node) => (
              <tr key={node.id} className="border-b border-border last:border-0">
                <td className="px-3 py-1.5">
                  <CopyableId id={node.id} shorten className="text-xs" />
                </td>
                <td className="px-3 py-1.5 text-xs text-muted-foreground">{humanizeEnum(node.type)}</td>
                <td className="px-3 py-1.5 text-xs text-muted-foreground">
                  {node.status ? humanizeEnum(node.status) : '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="overflow-x-auto rounded-lg border border-border bg-card scrollbar-thin">
        <table className="w-full text-sm">
          <caption className="border-b border-border px-3 py-2 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Relationships ({graph.edges.length})
          </caption>
          <thead>
            <tr className="border-b border-border text-xs text-muted-foreground">
              <th scope="col" className="px-3 py-1.5 text-left font-medium">From</th>
              <th scope="col" className="px-3 py-1.5 text-left font-medium">Relation</th>
              <th scope="col" className="px-3 py-1.5 text-left font-medium">To</th>
            </tr>
          </thead>
          <tbody>
            {graph.edges.map((edge, index) => (
              <tr key={`${edge.from}-${edge.to}-${index}`} className="border-b border-border last:border-0">
                <td className="mono-id px-3 py-1.5 text-xs">{edge.from}</td>
                <td className="px-3 py-1.5 text-xs text-muted-foreground">
                  {humanizeEnum(String(edge.relation))}
                </td>
                <td className="mono-id px-3 py-1.5 text-xs">{edge.to}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function truncate(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}
