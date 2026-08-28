/**
 * Money formatting. Contract 5: money is always (amountMinor, currency) and is formatted
 * ONLY at render time.
 *
 * The exponent is per currency: JPY and KRW have 0 minor digits, KWD and BHD have 3.
 * Dividing by a hardcoded 100 silently corrupts those, so every conversion here goes
 * through `currencyExponent()`. The integer/fraction split is done with integer arithmetic
 * so no float ever touches a monetary value on its way to the screen.
 */
import type { Money } from '@/lib/types/common';

/** ISO-4217 minor-unit exponents that are not 2. Everything else defaults to 2. */
const EXPONENT_OVERRIDES: Readonly<Record<string, number>> = {
  BIF: 0,
  CLP: 0,
  DJF: 0,
  GNF: 0,
  ISK: 0,
  JPY: 0,
  KMF: 0,
  KRW: 0,
  PYG: 0,
  RWF: 0,
  UGX: 0,
  UYI: 0,
  VND: 0,
  VUV: 0,
  XAF: 0,
  XOF: 0,
  XPF: 0,
  BHD: 3,
  IQD: 3,
  JOD: 3,
  KWD: 3,
  LYD: 3,
  OMR: 3,
  TND: 3,
  CLF: 4,
  UYW: 4,
};

const DEFAULT_EXPONENT = 2;

/** Minor-unit digits for an ISO-4217 code. Falls back to the Intl table, then to 2. */
export function currencyExponent(currency: string): number {
  const code = currency?.toUpperCase?.() ?? '';
  const override = EXPONENT_OVERRIDES[code];
  if (override !== undefined) return override;
  try {
    const resolved = new Intl.NumberFormat('en', {
      style: 'currency',
      currency: code,
    }).resolvedOptions();
    return resolved.maximumFractionDigits ?? DEFAULT_EXPONENT;
  } catch {
    return DEFAULT_EXPONENT;
  }
}

export interface MoneyFormatOptions {
  /** BCP-47 locale. Defaults to `en-US` so SSR and the client agree. */
  locale?: string;
  /** `symbol` renders the currency sign, `code` renders `INR 12,999.00`, `none` omits it. */
  display?: 'symbol' | 'code' | 'none';
  /** Abbreviate large values (1.3M) - stat tiles only, never in a ledger column. */
  compact?: boolean;
  /** Always show a leading + for positive amounts (deltas). */
  signed?: boolean;
}

const DEFAULT_LOCALE = 'en-US';

function pow10(exponent: number): number {
  let result = 1;
  for (let i = 0; i < exponent; i += 1) result *= 10;
  return result;
}

/** Splits minor units into sign, integer part and zero-padded fraction, without floats. */
function splitMinor(amountMinor: number, exponent: number) {
  const safe = Number.isFinite(amountMinor) ? Math.trunc(amountMinor) : 0;
  const negative = safe < 0;
  const abs = Math.abs(safe);
  const factor = pow10(exponent);
  const whole = Math.trunc(abs / factor);
  const fraction = abs - whole * factor;
  return { negative, whole, fraction: String(fraction).padStart(exponent, '0') };
}

/**
 * `{ amountMinor: 1299900, currency: 'INR' }` -> `₹12,999.00`.
 * `{ amountMinor: 129990, currency: 'JPY' }`  -> `¥129,990`   (exponent 0, no fake decimals)
 * `{ amountMinor: 1299900, currency: 'KWD' }` -> `KWD 1,299.900` (exponent 3)
 */
export function formatMoney(money: Money | null | undefined, options: MoneyFormatOptions = {}): string {
  if (!money) return '-';
  const { locale = DEFAULT_LOCALE, display = 'symbol', compact = false, signed = false } = options;
  const currency = (money.currency ?? '').toUpperCase();
  const exponent = currencyExponent(currency);

  if (compact) {
    // Compact notation is inherently approximate; a float is acceptable here and only here.
    const approximate = money.amountMinor / pow10(exponent);
    const formatter = new Intl.NumberFormat(locale, {
      notation: 'compact',
      maximumFractionDigits: 1,
      ...(display === 'symbol' ? { style: 'currency' as const, currency } : {}),
      ...(signed ? { signDisplay: 'exceptZero' as const } : {}),
    });
    const body = formatter.format(approximate);
    return display === 'code' ? `${currency} ${body}` : body;
  }

  const { negative, whole, fraction } = splitMinor(money.amountMinor, exponent);
  const groupedWhole = new Intl.NumberFormat(locale, { useGrouping: true }).format(whole);
  const decimalSeparator =
    new Intl.NumberFormat(locale)
      .formatToParts(1.1)
      .find((part) => part.type === 'decimal')?.value ?? '.';
  const numeric = exponent > 0 ? `${groupedWhole}${decimalSeparator}${fraction}` : groupedWhole;
  const sign = negative ? '-' : signed && money.amountMinor > 0 ? '+' : '';

  if (display === 'none') return `${sign}${numeric}`;
  if (display === 'code') return `${sign}${currency} ${numeric}`;
  return `${sign}${currencySymbol(currency, locale)}${numeric}`;
}

/** The currency sign for a code, e.g. INR -> ₹. Falls back to the code itself plus a space. */
export function currencySymbol(currency: string, locale: string = DEFAULT_LOCALE): string {
  const code = currency?.toUpperCase?.() ?? '';
  if (!code) return '';
  try {
    const parts = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: code,
      currencyDisplay: 'narrowSymbol',
    }).formatToParts(0);
    const symbol = parts.find((part) => part.type === 'currency')?.value;
    return symbol && symbol !== code ? symbol : `${code} `;
  } catch {
    return `${code} `;
  }
}

/** Short form for KPI tiles: `₹1.3M`. */
export function formatMoneyCompact(money: Money | null | undefined, locale?: string): string {
  return formatMoney(money, { compact: true, ...(locale ? { locale } : {}) });
}

/** `INR 12,999.00` - the display form mirroring `Money.toDisplayString()` on the Java side. */
export function formatMoneyWithCode(money: Money | null | undefined, locale?: string): string {
  return formatMoney(money, { display: 'code', ...(locale ? { locale } : {}) });
}

export function isZeroMoney(money: Money | null | undefined): boolean {
  return !money || money.amountMinor === 0;
}

export function moneyOf(amountMinor: number, currency: string): Money {
  return { amountMinor: Math.trunc(amountMinor), currency: currency.toUpperCase() };
}

export function zeroMoney(currency: string): Money {
  return moneyOf(0, currency);
}

/** Adds two amounts. Throws on a currency mismatch, mirroring `CurrencyMismatchException`. */
export function addMoney(left: Money, right: Money): Money {
  if (left.currency.toUpperCase() !== right.currency.toUpperCase()) {
    throw new Error(`Currency mismatch: ${left.currency} vs ${right.currency}`);
  }
  return moneyOf(left.amountMinor + right.amountMinor, left.currency);
}

/** Sums a list; returns a zero of `fallbackCurrency` when the list is empty. */
export function sumMoney(items: readonly Money[], fallbackCurrency = 'USD'): Money {
  if (items.length === 0) return zeroMoney(fallbackCurrency);
  return items.reduce((acc, item) => addMoney(acc, item));
}

/**
 * Parses user input in major units into minor units, e.g. `"1,299.5"` + INR -> 129950.
 * Returns null for anything unparseable so a form can show a validation message.
 */
export function parseMoneyInput(input: string, currency: string): Money | null {
  const exponent = currencyExponent(currency);
  const cleaned = input.replace(/[\s,]/g, '');
  if (!/^-?\d*(\.\d*)?$/.test(cleaned) || cleaned === '' || cleaned === '-') return null;
  const negative = cleaned.startsWith('-');
  const [wholeRaw = '0', fractionRaw = ''] = cleaned.replace('-', '').split('.');
  const fraction = fractionRaw.slice(0, exponent).padEnd(exponent, '0');
  const minor = Number(wholeRaw) * pow10(exponent) + (exponent > 0 ? Number(fraction) : 0);
  if (!Number.isFinite(minor)) return null;
  return moneyOf(negative ? -minor : minor, currency);
}

/** Basis points -> percent string, e.g. 7100 -> `71.0%`. Used for merchant win rates. */
export function formatBps(bps: number | null | undefined, fractionDigits = 1): string {
  if (bps === null || bps === undefined) return '-';
  // eslint-disable-next-line no-restricted-syntax -- basis points, not money: 7100 bps = 71.0%
  return `${(bps / 100).toFixed(fractionDigits)}%`;
}

/** Ratio in [0,1] -> percent string, e.g. 0.71 -> `71%`. */
export function formatRatio(ratio: number | null | undefined, fractionDigits = 0): string {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) return '-';
  return `${(ratio * 100).toFixed(fractionDigits)}%`;
}
