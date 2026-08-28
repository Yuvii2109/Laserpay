import { cn } from '@/lib/utils';
import { formatMoney, formatMoneyWithCode, type MoneyFormatOptions } from '@/lib/format/money';
import type { Money } from '@/lib/types/common';

export interface MoneyDisplayProps extends MoneyFormatOptions {
  money: Money | null | undefined;
  className?: string;
  /** Dim the value (secondary figures in a detail panel). */
  muted?: boolean;
  /** Show the ISO code beside the amount instead of the symbol. */
  withCode?: boolean;
}

/**
 * The only sanctioned way to put money on screen. It formats from `amountMinor` using the
 * currency's own exponent (JPY 0, INR/GBP 2, KWD 3) and never divides by a hardcoded 100.
 * The title attribute always carries the unambiguous `CODE 1,234.00` form.
 */
export function MoneyDisplay({
  money,
  className,
  muted = false,
  withCode = false,
  ...options
}: MoneyDisplayProps) {
  if (!money) {
    return <span className={cn('text-muted-foreground', className)}>-</span>;
  }
  const rendered = withCode
    ? formatMoneyWithCode(money, options.locale)
    : formatMoney(money, options);
  return (
    <span
      className={cn('tabular whitespace-nowrap', muted && 'text-muted-foreground', className)}
      title={formatMoneyWithCode(money, options.locale)}
      data-currency={money.currency}
      data-amount-minor={money.amountMinor}
    >
      {rendered}
    </span>
  );
}
