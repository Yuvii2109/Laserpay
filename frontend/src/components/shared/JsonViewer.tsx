'use client';

import * as React from 'react';
import { ChevronDown, ChevronRight, Copy } from 'lucide-react';
import { cn } from '@/lib/utils';
import { toast } from '@/components/ui/sonner';

export interface JsonViewerProps {
  value: unknown;
  className?: string;
  /** Depth that starts expanded. 1 shows the top level only. */
  defaultExpandedDepth?: number;
  /** Show the copy-to-clipboard button. */
  copyable?: boolean;
  maxHeight?: string;
}

/**
 * Collapsible JSON inspector for event payloads, policy constraints, chaos targets and the
 * raw shapes behind a screen. Read-only by construction - the console never edits raw state.
 */
export function JsonViewer({
  value,
  className,
  defaultExpandedDepth = 2,
  copyable = true,
  maxHeight = '28rem',
}: JsonViewerProps) {
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(JSON.stringify(value, null, 2));
      toast.success('JSON copied');
    } catch {
      toast.error('Clipboard is unavailable in this browser');
    }
  };

  return (
    <div className={cn('relative rounded-lg border border-border bg-card', className)}>
      {copyable ? (
        <button
          type="button"
          onClick={copy}
          className="absolute right-2 top-2 rounded p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          aria-label="Copy JSON"
        >
          <Copy className="size-3.5" />
        </button>
      ) : null}
      <div
        className="overflow-auto scrollbar-thin p-3 font-[family-name:var(--font-mono)] text-xs leading-relaxed"
        style={{ maxHeight }}
      >
        <JsonNode name={null} value={value} depth={0} defaultExpandedDepth={defaultExpandedDepth} />
      </div>
    </div>
  );
}

function JsonNode({
  name,
  value,
  depth,
  defaultExpandedDepth,
}: {
  name: string | null;
  value: unknown;
  depth: number;
  defaultExpandedDepth: number;
}) {
  const [expanded, setExpanded] = React.useState(depth < defaultExpandedDepth);

  const isArray = Array.isArray(value);
  const isObject = !isArray && typeof value === 'object' && value !== null;

  if (!isArray && !isObject) {
    return (
      <div className="flex gap-1.5" style={{ paddingLeft: depth * 12 }}>
        {name !== null ? <span className="text-muted-foreground">{name}:</span> : null}
        <ScalarValue value={value} />
      </div>
    );
  }

  const entries: [string, unknown][] = isArray
    ? (value as unknown[]).map((item, index) => [String(index), item])
    : Object.entries(value as Record<string, unknown>);

  const summary = isArray ? `Array(${entries.length})` : `{${entries.length}}`;

  return (
    <div style={{ paddingLeft: depth * 12 }}>
      <button
        type="button"
        onClick={() => setExpanded((current) => !current)}
        className="inline-flex items-center gap-1 rounded-sm text-foreground hover:bg-accent/60"
      >
        {expanded ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
        {name !== null ? <span className="text-muted-foreground">{name}:</span> : null}
        <span className="text-muted-foreground">{summary}</span>
      </button>
      {expanded
        ? entries.map(([key, child]) => (
            <JsonNode
              key={key}
              name={key}
              value={child}
              depth={depth + 1}
              defaultExpandedDepth={defaultExpandedDepth}
            />
          ))
        : null}
    </div>
  );
}

function ScalarValue({ value }: { value: unknown }) {
  if (value === null) return <span className="text-muted-foreground">null</span>;
  if (value === undefined) return <span className="text-muted-foreground">undefined</span>;
  if (typeof value === 'string') {
    return <span style={{ color: 'var(--chart-3)' }}>&quot;{value}&quot;</span>;
  }
  if (typeof value === 'number') return <span style={{ color: 'var(--chart-1)' }}>{value}</span>;
  if (typeof value === 'boolean') {
    return <span style={{ color: 'var(--chart-7)' }}>{String(value)}</span>;
  }
  return <span>{String(value)}</span>;
}
