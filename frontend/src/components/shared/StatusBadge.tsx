import {
  AlertTriangle,
  CircleCheck,
  CircleDashed,
  CircleDot,
  CircleSlash,
  Clock,
  FlaskConical,
  Hourglass,
  OctagonX,
  ShieldCheck,
  ShieldX,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { humanizeEnum } from '@/lib/format/id';
import {
  CASE_STATUS_TONE,
  DISPUTE_STATUS_TONE,
  EVIDENCE_STATUS_TONE,
  SAFETY_DECISION_TONE,
  SEVERITY_LABEL,
  SEVERITY_TONE,
  toneColorVar,
  type Tone,
} from '@/lib/format/score';
import type { CaseStatus } from '@/lib/types/case';
import type { DisputeStatus } from '@/lib/types/dispute';
import type { EvidenceStatus } from '@/lib/types/evidence';
import type { GapSeverity } from '@/lib/types/readiness';
import type { SafetyDecision } from '@/lib/types/ai';
import type { ChaosStatus, SimulationStatus } from '@/lib/types/simulation';

/**
 * One badge per status enum in contract 6. Status colour is reserved and is always paired
 * with an icon and a text label, so the state survives colour-blindness, greyscale printing
 * and forced-colors mode.
 */
export type StatusKind =
  | 'evidence'
  | 'dispute'
  | 'case'
  | 'severity'
  | 'safety'
  | 'simulation'
  | 'chaos';

export type StatusValue =
  | EvidenceStatus
  | DisputeStatus
  | CaseStatus
  | GapSeverity
  | SafetyDecision
  | SimulationStatus
  | ChaosStatus;

const SIMULATION_TONE: Readonly<Record<SimulationStatus, Tone>> = {
  PENDING: 'neutral',
  RUNNING: 'info',
  COMPLETED: 'good',
  STOPPED: 'warning',
  FAILED: 'critical',
};

const CHAOS_TONE: Readonly<Record<ChaosStatus, Tone>> = {
  REQUESTED: 'info',
  APPLIED: 'good',
  FAILED: 'critical',
  CANCELLED: 'neutral',
};

const TONE_ICON: Readonly<Record<Tone, LucideIcon>> = {
  good: CircleCheck,
  warning: AlertTriangle,
  serious: Clock,
  critical: OctagonX,
  neutral: CircleDashed,
  info: CircleDot,
};

/** Icons that say more than the tone alone for a handful of specific states. */
const SPECIFIC_ICON: Partial<Record<string, LucideIcon>> = {
  'evidence:SUPERSEDED': CircleSlash,
  'evidence:PENDING': Hourglass,
  'case:AWAITING_APPROVAL': Hourglass,
  'case:AWAITING_EVIDENCE': Hourglass,
  'dispute:AWAITING_HUMAN_REVIEW': Hourglass,
  'safety:ALLOW': ShieldCheck,
  'safety:DENY': ShieldX,
  'simulation:RUNNING': FlaskConical,
};

function toneFor(kind: StatusKind, value: StatusValue): Tone {
  switch (kind) {
    case 'evidence':
      return EVIDENCE_STATUS_TONE[value as EvidenceStatus] ?? 'neutral';
    case 'dispute':
      return DISPUTE_STATUS_TONE[value as DisputeStatus] ?? 'neutral';
    case 'case':
      return CASE_STATUS_TONE[value as CaseStatus] ?? 'neutral';
    case 'severity':
      return SEVERITY_TONE[value as GapSeverity] ?? 'neutral';
    case 'safety':
      return SAFETY_DECISION_TONE[value as SafetyDecision] ?? 'neutral';
    case 'simulation':
      return SIMULATION_TONE[value as SimulationStatus] ?? 'neutral';
    case 'chaos':
      return CHAOS_TONE[value as ChaosStatus] ?? 'neutral';
    default:
      return 'neutral';
  }
}

export interface StatusBadgeProps {
  kind: StatusKind;
  value: StatusValue | null | undefined;
  className?: string;
  /** Hide the text label (dense table cells). The label stays in the accessible name. */
  iconOnly?: boolean;
}

export function StatusBadge({ kind, value, className, iconOnly = false }: StatusBadgeProps) {
  if (!value) return <span className={cn('text-muted-foreground', className)}>—</span>;

  const tone = toneFor(kind, value);
  const Icon = SPECIFIC_ICON[`${kind}:${value}`] ?? TONE_ICON[tone];
  const label = kind === 'severity' ? SEVERITY_LABEL[value as GapSeverity] : humanizeEnum(value);
  const color = toneColorVar(tone);

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium',
        className,
      )}
      style={{
        color,
        borderColor: `color-mix(in oklab, ${color} 35%, transparent)`,
        backgroundColor: `color-mix(in oklab, ${color} 12%, transparent)`,
      }}
      title={label}
      data-tone={tone}
      data-status={value}
    >
      <Icon className="size-3.5 shrink-0" aria-hidden />
      {iconOnly ? <span className="sr-only">{label}</span> : label}
    </span>
  );
}
