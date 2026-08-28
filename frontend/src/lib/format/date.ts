/**
 * Time formatting. Contract 5: every timestamp on the wire is an ISO-8601 instant in UTC.
 *
 * Absolute renderings default to UTC and say so, because an evidence capture time that
 * silently shifts by the viewer's timezone is an audit problem, not a UX nicety. Relative
 * renderings ("4m ago") are timezone-free by construction.
 */
import { formatDistanceToNowStrict, formatDistanceStrict, differenceInMilliseconds } from 'date-fns';
import type { Iso8601 } from '@/lib/types/common';

export const EM_DASH = '-';

function toDate(value: Iso8601 | Date | null | undefined): Date | null {
  if (!value) return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export type TimeZoneMode = 'utc' | 'local';

const utcFormatter = (options: Intl.DateTimeFormatOptions) =>
  new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', ...options });

const localFormatter = (options: Intl.DateTimeFormatOptions) =>
  new Intl.DateTimeFormat('en-GB', options);

/** `26 Aug 2026, 10:15:30 UTC` - the default absolute rendering. */
export function formatInstant(
  value: Iso8601 | Date | null | undefined,
  mode: TimeZoneMode = 'utc',
): string {
  const date = toDate(value);
  if (!date) return EM_DASH;
  const options: Intl.DateTimeFormatOptions = {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  };
  const formatted =
    mode === 'utc' ? utcFormatter(options).format(date) : localFormatter(options).format(date);
  return mode === 'utc' ? `${formatted} UTC` : formatted;
}

/** `26 Aug 2026` - date only. */
export function formatDate(
  value: Iso8601 | Date | null | undefined,
  mode: TimeZoneMode = 'utc',
): string {
  const date = toDate(value);
  if (!date) return EM_DASH;
  const options: Intl.DateTimeFormatOptions = {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  };
  return mode === 'utc' ? utcFormatter(options).format(date) : localFormatter(options).format(date);
}

/** `10:15:30` - time only, for dense timeline rows where the date is already in the group header. */
export function formatTime(
  value: Iso8601 | Date | null | undefined,
  mode: TimeZoneMode = 'utc',
): string {
  const date = toDate(value);
  if (!date) return EM_DASH;
  const options: Intl.DateTimeFormatOptions = {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  };
  return mode === 'utc' ? utcFormatter(options).format(date) : localFormatter(options).format(date);
}

/** `4m ago` / `in 2d`. Timezone-independent. */
export function formatRelative(value: Iso8601 | Date | null | undefined): string {
  const date = toDate(value);
  if (!date) return EM_DASH;
  return formatDistanceToNowStrict(date, { addSuffix: true });
}

/** `4m` - the same distance without the suffix, for compact badges. */
export function formatRelativeShort(value: Iso8601 | Date | null | undefined): string {
  const date = toDate(value);
  if (!date) return EM_DASH;
  return formatDistanceToNowStrict(date);
}

/** Distance between two instants, e.g. case assembly duration. */
export function formatSpan(
  from: Iso8601 | Date | null | undefined,
  to: Iso8601 | Date | null | undefined,
): string {
  const start = toDate(from);
  const end = toDate(to);
  if (!start || !end) return EM_DASH;
  return formatDistanceStrict(start, end);
}

/** `1.2s` / `340ms` - latency rendering for AI and workflow panels. */
export function formatLatency(millis: number | null | undefined): string {
  if (millis === null || millis === undefined || !Number.isFinite(millis)) return EM_DASH;
  if (millis < 1000) return `${Math.round(millis)}ms`;
  if (millis < 60_000) return `${(millis / 1000).toFixed(1)}s`;
  const minutes = Math.floor(millis / 60_000);
  const seconds = Math.round((millis % 60_000) / 1000);
  return `${minutes}m ${seconds}s`;
}

export interface DeadlineState {
  /** Milliseconds remaining; negative once the deadline has passed. */
  millisRemaining: number;
  hoursRemaining: number;
  passed: boolean;
  /** True inside the contract 9.4 urgency window (< 48h). */
  urgent: boolean;
  label: string;
}

/** Deadline arithmetic used by dispute/case surfaces. Contract 9.4 treats <48h as urgent. */
export function deadlineState(
  deadlineAt: Iso8601 | Date | null | undefined,
  now: Date = new Date(),
): DeadlineState | null {
  const date = toDate(deadlineAt);
  if (!date) return null;
  const millisRemaining = differenceInMilliseconds(date, now);
  const hoursRemaining = millisRemaining / 3_600_000;
  const passed = millisRemaining <= 0;
  return {
    millisRemaining,
    hoursRemaining,
    passed,
    urgent: !passed && hoursRemaining < 48,
    label: passed
      ? `overdue ${formatDistanceStrict(date, now)}`
      : `${formatDistanceStrict(now, date)} left`,
  };
}

/** Current instant as an ISO-8601 UTC string - the only way this app creates timestamps. */
export function nowIso(): Iso8601 {
  return new Date().toISOString();
}

/** `n` days ago as ISO-8601 UTC, for default time-range filters. */
export function daysAgoIso(days: number, from: Date = new Date()): Iso8601 {
  return new Date(from.getTime() - days * 86_400_000).toISOString();
}

/** Sort comparator for instants, newest first. */
export function byInstantDesc(a: Iso8601 | null, b: Iso8601 | null): number {
  return (toDate(b)?.getTime() ?? 0) - (toDate(a)?.getTime() ?? 0);
}
