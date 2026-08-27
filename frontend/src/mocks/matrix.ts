/**
 * The default requirement matrix, mirrored from
 * `com.laserpay.pdei.core.policy.DefaultPolicyMatrix` so mock readiness scores agree with
 * what the real engine would compute for the same evidence set (contract 7).
 *
 * If the Java matrix changes, change this file with it - it is a mirror, not a second opinion.
 */
import type { DisputeReasonCode } from '@/lib/types/dispute';
import type { EvidenceType } from '@/lib/types/evidence';
import type { RequirementStrength } from '@/lib/types/readiness';
import type { RequirementSpec } from '@/lib/types/policy';

export const DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE = 0.9;
export const DEFAULT_MAX_CONTRADICTIONS = 0;
export const DEFAULT_MIN_READINESS_FOR_AUTO_PREPARE = 75;
/** INR 50,000.00 in minor units. Above this a human always reviews. */
export const DEFAULT_HUMAN_REVIEW_ABOVE_AMOUNT_MINOR = 5_000_000;
export const DEFAULT_RESPONSE_WINDOW_DAYS = 21;
/** Contract 7: expiry within 7 days is EXPIRING_SOON. */
export const DEFAULT_EXPIRING_SOON_DAYS = 7;

export const MAX_AGE_DAYS: Readonly<Partial<Record<EvidenceType, number>>> = {
  PAYMENT_PROOF: 3650,
  INVOICE: 3650,
  ORDER_RECORD: 3650,
  REFUND_RECEIPT: 3650,
  SIGNED_CONTRACT: 3650,
  SHIPPING_RECORD: 540,
  DELIVERY_PROOF: 540,
  CUSTOMER_COMMUNICATION: 730,
  PRIOR_TRANSACTION_HISTORY: 730,
  MERCHANT_POLICY: 365,
  TERMS_OF_SERVICE: 365,
  AVS_CVV_RESULT: 180,
  DEVICE_FINGERPRINT: 180,
};

type Row = [EvidenceType, RequirementStrength];

const MATRIX: Readonly<Record<DisputeReasonCode, readonly Row[]>> = {
  GOODS_NOT_RECEIVED: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['INVOICE', 'MANDATORY'],
    ['SHIPPING_RECORD', 'MANDATORY'],
    ['DELIVERY_PROOF', 'MANDATORY'],
    ['MERCHANT_POLICY', 'MANDATORY'],
    ['ORDER_RECORD', 'RECOMMENDED'],
    ['CUSTOMER_COMMUNICATION', 'RECOMMENDED'],
    ['TERMS_OF_SERVICE', 'OPTIONAL'],
    ['PRIOR_TRANSACTION_HISTORY', 'OPTIONAL'],
  ],
  SERVICE_NOT_RENDERED: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['INVOICE', 'MANDATORY'],
    ['SIGNED_CONTRACT', 'MANDATORY'],
    ['MERCHANT_POLICY', 'MANDATORY'],
    ['CUSTOMER_COMMUNICATION', 'RECOMMENDED'],
    ['ORDER_RECORD', 'RECOMMENDED'],
    ['TERMS_OF_SERVICE', 'RECOMMENDED'],
    ['PRIOR_TRANSACTION_HISTORY', 'OPTIONAL'],
  ],
  PRODUCT_NOT_AS_DESCRIBED: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['INVOICE', 'MANDATORY'],
    ['ORDER_RECORD', 'MANDATORY'],
    ['DELIVERY_PROOF', 'MANDATORY'],
    ['MERCHANT_POLICY', 'MANDATORY'],
    ['CUSTOMER_COMMUNICATION', 'RECOMMENDED'],
    ['TERMS_OF_SERVICE', 'RECOMMENDED'],
    ['SHIPPING_RECORD', 'RECOMMENDED'],
    ['PRIOR_TRANSACTION_HISTORY', 'OPTIONAL'],
  ],
  DUPLICATE_PROCESSING: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['INVOICE', 'MANDATORY'],
    ['PRIOR_TRANSACTION_HISTORY', 'MANDATORY'],
    ['ORDER_RECORD', 'RECOMMENDED'],
    ['REFUND_RECEIPT', 'RECOMMENDED'],
    ['CUSTOMER_COMMUNICATION', 'OPTIONAL'],
  ],
  CREDIT_NOT_PROCESSED: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['REFUND_RECEIPT', 'MANDATORY'],
    ['MERCHANT_POLICY', 'MANDATORY'],
    ['CUSTOMER_COMMUNICATION', 'RECOMMENDED'],
    ['INVOICE', 'RECOMMENDED'],
    ['ORDER_RECORD', 'OPTIONAL'],
    ['TERMS_OF_SERVICE', 'OPTIONAL'],
  ],
  SUBSCRIPTION_CANCELLED: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['TERMS_OF_SERVICE', 'MANDATORY'],
    ['CUSTOMER_COMMUNICATION', 'MANDATORY'],
    ['MERCHANT_POLICY', 'MANDATORY'],
    ['INVOICE', 'RECOMMENDED'],
    ['SIGNED_CONTRACT', 'RECOMMENDED'],
    ['PRIOR_TRANSACTION_HISTORY', 'OPTIONAL'],
  ],
  FRAUDULENT_TRANSACTION: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['AVS_CVV_RESULT', 'MANDATORY'],
    ['DEVICE_FINGERPRINT', 'MANDATORY'],
    ['DELIVERY_PROOF', 'MANDATORY'],
    ['PRIOR_TRANSACTION_HISTORY', 'RECOMMENDED'],
    ['SHIPPING_RECORD', 'RECOMMENDED'],
    ['ORDER_RECORD', 'RECOMMENDED'],
    ['CUSTOMER_COMMUNICATION', 'OPTIONAL'],
  ],
  UNRECOGNIZED_TRANSACTION: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['INVOICE', 'MANDATORY'],
    ['ORDER_RECORD', 'MANDATORY'],
    ['PRIOR_TRANSACTION_HISTORY', 'RECOMMENDED'],
    ['DEVICE_FINGERPRINT', 'RECOMMENDED'],
    ['CUSTOMER_COMMUNICATION', 'RECOMMENDED'],
    ['AVS_CVV_RESULT', 'RECOMMENDED'],
    ['DELIVERY_PROOF', 'OPTIONAL'],
  ],
  INCORRECT_AMOUNT: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['INVOICE', 'MANDATORY'],
    ['ORDER_RECORD', 'MANDATORY'],
    ['MERCHANT_POLICY', 'RECOMMENDED'],
    ['CUSTOMER_COMMUNICATION', 'RECOMMENDED'],
    ['REFUND_RECEIPT', 'RECOMMENDED'],
    ['TERMS_OF_SERVICE', 'OPTIONAL'],
  ],
  PAID_BY_OTHER_MEANS: [
    ['PAYMENT_PROOF', 'MANDATORY'],
    ['INVOICE', 'MANDATORY'],
    ['PRIOR_TRANSACTION_HISTORY', 'MANDATORY'],
    ['ORDER_RECORD', 'RECOMMENDED'],
    ['CUSTOMER_COMMUNICATION', 'RECOMMENDED'],
    ['REFUND_RECEIPT', 'OPTIONAL'],
  ],
};

const STRENGTH_WEIGHT: Readonly<Record<RequirementStrength, number>> = {
  MANDATORY: 3,
  RECOMMENDED: 2,
  OPTIONAL: 1,
  PROHIBITED: 0,
};

function toSpec(row: Row): RequirementSpec {
  const [type, strength] = row;
  return {
    type,
    strength,
    weight: STRENGTH_WEIGHT[strength],
    maxAgeDays: MAX_AGE_DAYS[type] ?? null,
    provenanceRequired: strength === 'MANDATORY',
    minQualityScore: 0,
    note: null,
  };
}

/** Reason codes used for the baseline profile when no dispute has been raised yet. */
export const DEFAULT_TOP_REASON_CODES: readonly DisputeReasonCode[] = [
  'GOODS_NOT_RECEIVED',
  'FRAUDULENT_TRANSACTION',
  'PRODUCT_NOT_AS_DESCRIBED',
];

/** Requirements for one reason code, or the baseline profile when none is supplied. */
export function requirementsFor(reasonCode: DisputeReasonCode | null): RequirementSpec[] {
  if (!reasonCode) return baselineRequirements(DEFAULT_TOP_REASON_CODES);
  return MATRIX[reasonCode].map(toSpec);
}

/**
 * Contract 7 baseline profile: the union of MANDATORY requirements across the supplied reason
 * codes, with everything in the union staying MANDATORY.
 */
export function baselineRequirements(reasonCodes: readonly DisputeReasonCode[]): RequirementSpec[] {
  const mandatory: EvidenceType[] = [];
  const recommended: EvidenceType[] = [];
  for (const code of reasonCodes) {
    for (const [type, strength] of MATRIX[code]) {
      if (strength === 'MANDATORY' && !mandatory.includes(type)) mandatory.push(type);
      else if (strength === 'RECOMMENDED' && !recommended.includes(type)) recommended.push(type);
    }
  }
  return [
    ...mandatory.map((type) => toSpec([type, 'MANDATORY'])),
    ...recommended.filter((type) => !mandatory.includes(type)).map((type) => toSpec([type, 'RECOMMENDED'])),
  ];
}
