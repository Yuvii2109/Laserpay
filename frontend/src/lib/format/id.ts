/**
 * Id helpers. Contract 5 ids are human-readable and prefixed (`TX-`, `EV-`, `CASE-`, ...),
 * which lets the UI resolve an id to its detail route without a lookup.
 */
import { ID_PREFIX } from '@/lib/types/common';

export type EntityKind =
  | 'merchant'
  | 'customer'
  | 'transaction'
  | 'payment'
  | 'order'
  | 'shipment'
  | 'delivery'
  | 'refund'
  | 'communication'
  | 'evidence'
  | 'policy'
  | 'dispute'
  | 'case'
  | 'investigation'
  | 'audit'
  | 'simulation'
  | 'unknown';

/**
 * Longest prefix first: `CASE-` must be tested before `C`-style prefixes, and `CUS-`
 * must not swallow `CASE-`.
 */
const PREFIX_TO_KIND: readonly [string, EntityKind][] = [
  [ID_PREFIX.CASE, 'case'],
  [ID_PREFIX.MERCHANT, 'merchant'],
  [ID_PREFIX.CUSTOMER, 'customer'],
  [ID_PREFIX.TRANSACTION, 'transaction'],
  [ID_PREFIX.PAYMENT, 'payment'],
  [ID_PREFIX.ORDER, 'order'],
  [ID_PREFIX.SHIPMENT, 'shipment'],
  [ID_PREFIX.DELIVERY, 'delivery'],
  [ID_PREFIX.REFUND, 'refund'],
  [ID_PREFIX.COMMUNICATION, 'communication'],
  [ID_PREFIX.EVIDENCE, 'evidence'],
  [ID_PREFIX.POLICY, 'policy'],
  [ID_PREFIX.DISPUTE, 'dispute'],
  [ID_PREFIX.INVESTIGATION, 'investigation'],
  [ID_PREFIX.AUDIT, 'audit'],
  [ID_PREFIX.SIMULATION, 'simulation'],
];

export function entityKind(id: string | null | undefined): EntityKind {
  if (!id) return 'unknown';
  for (const [prefix, kind] of PREFIX_TO_KIND) {
    if (id.startsWith(prefix)) return kind;
  }
  return 'unknown';
}

export function hasPrefix(id: string | null | undefined, prefix: string): boolean {
  return Boolean(id && id.startsWith(prefix));
}

/**
 * Detail route for an id, or null when the entity has no page of its own
 * (payments, orders and shipments live inside the transaction detail page).
 */
export function hrefForId(id: string | null | undefined): string | null {
  if (!id) return null;
  switch (entityKind(id)) {
    case 'transaction':
      return `/transactions/${id}`;
    case 'evidence':
      return `/evidence/${id}`;
    case 'dispute':
      return `/disputes/${id}`;
    case 'case':
      return `/cases/${id}`;
    case 'policy':
      return `/policies?policyId=${encodeURIComponent(id)}`;
    case 'simulation':
      return `/simulation?runId=${encodeURIComponent(id)}`;
    case 'audit':
      return `/observability?auditId=${encodeURIComponent(id)}`;
    default:
      return null;
  }
}

/**
 * `EV-0f3c9a12b4` -> `EV-0f3c…b4`. Keeps the prefix and both ends so an operator can still
 * eyeball-match it against a log line.
 */
export function shortenId(id: string | null | undefined, head = 8, tail = 4): string {
  if (!id) return '-';
  if (id.length <= head + tail + 1) return id;
  return `${id.slice(0, head)}…${id.slice(-tail)}`;
}

/** `9f1c0b7e-...` -> `9f1c0b7e`. For correlation/event UUIDs in dense tables. */
export function shortenUuid(uuid: string | null | undefined): string {
  if (!uuid) return '-';
  const [first] = uuid.split('-');
  return first ?? uuid.slice(0, 8);
}

/** `a3f9...c1` for a sha256, keeping enough to compare two hashes by eye. */
export function shortenHash(sha256: string | null | undefined): string {
  if (!sha256) return '-';
  return sha256.length <= 16 ? sha256 : `${sha256.slice(0, 10)}…${sha256.slice(-6)}`;
}

/** MinIO object key -> the filename at its tail (contract 11 layout). */
export function objectKeyFilename(objectKey: string | null | undefined): string {
  if (!objectKey) return '-';
  const parts = objectKey.split('/');
  return parts[parts.length - 1] ?? objectKey;
}

/** `SCREAMING_SNAKE_CASE` -> `Screaming snake case`, for enum labels. */
export function humanizeEnum(value: string | null | undefined): string {
  if (!value) return '-';
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** `PaymentCaptured` -> `Payment captured`, for EventType labels. */
export function humanizeEventType(value: string | null | undefined): string {
  if (!value) return '-';
  const spaced = value.replace(/([a-z0-9])([A-Z])/g, '$1 $2');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase();
}
