import {
  Banknote,
  BadgeCheck,
  Boxes,
  Contact,
  Fingerprint,
  FileSignature,
  FileText,
  History,
  MailOpen,
  PackageCheck,
  ReceiptText,
  ScrollText,
  Truck,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import type { EvidenceType } from '@/lib/types/evidence';

/** One icon per EvidenceType (contract 6). Shape carries meaning; colour never does. */
export const EVIDENCE_TYPE_ICON: Readonly<Record<EvidenceType, LucideIcon>> = {
  PAYMENT_PROOF: Banknote,
  INVOICE: ReceiptText,
  ORDER_RECORD: Boxes,
  SHIPPING_RECORD: Truck,
  DELIVERY_PROOF: PackageCheck,
  REFUND_RECEIPT: FileText,
  CUSTOMER_COMMUNICATION: MailOpen,
  MERCHANT_POLICY: ScrollText,
  TERMS_OF_SERVICE: FileText,
  AVS_CVV_RESULT: BadgeCheck,
  DEVICE_FINGERPRINT: Fingerprint,
  PRIOR_TRANSACTION_HISTORY: History,
  SIGNED_CONTRACT: FileSignature,
};

/** Short human label, e.g. `DELIVERY_PROOF` -> `Delivery proof`. */
export const EVIDENCE_TYPE_LABEL: Readonly<Record<EvidenceType, string>> = {
  PAYMENT_PROOF: 'Payment proof',
  INVOICE: 'Invoice',
  ORDER_RECORD: 'Order record',
  SHIPPING_RECORD: 'Shipping record',
  DELIVERY_PROOF: 'Delivery proof',
  REFUND_RECEIPT: 'Refund receipt',
  CUSTOMER_COMMUNICATION: 'Customer communication',
  MERCHANT_POLICY: 'Merchant policy',
  TERMS_OF_SERVICE: 'Terms of service',
  AVS_CVV_RESULT: 'AVS / CVV result',
  DEVICE_FINGERPRINT: 'Device fingerprint',
  PRIOR_TRANSACTION_HISTORY: 'Prior transaction history',
  SIGNED_CONTRACT: 'Signed contract',
};

export function EvidenceTypeIcon({
  type,
  className,
  withLabel = false,
}: {
  type: EvidenceType;
  className?: string;
  withLabel?: boolean;
}) {
  const Icon = EVIDENCE_TYPE_ICON[type] ?? Contact;
  if (!withLabel) {
    return <Icon className={cn('size-4 text-muted-foreground', className)} aria-label={EVIDENCE_TYPE_LABEL[type]} />;
  }
  return (
    <span className="inline-flex items-center gap-2">
      <Icon className={cn('size-4 shrink-0 text-muted-foreground', className)} aria-hidden />
      <span>{EVIDENCE_TYPE_LABEL[type]}</span>
    </span>
  );
}
