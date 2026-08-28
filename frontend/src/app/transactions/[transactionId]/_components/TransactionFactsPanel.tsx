'use client';

import { ChevronDown } from 'lucide-react';
import { MoneyDisplay, TimestampDisplay } from '@/components/shared';
import { humanizeEnum } from '@/lib/format/id';
import type { TransactionFacts } from '@/lib/types/transaction';

export interface TransactionFactsPanelProps {
  facts: TransactionFacts | undefined;
}

/**
 * The entity graph behind a transaction, as plain facts.
 *
 * These are the rows `state-builder-worker` projected out of the canonical event stream -
 * payments, orders, shipments, deliveries, refunds, communications. They are what evidence is
 * checked *against*, so they sit one disclosure away from the header rather than inside a tab.
 */
export function TransactionFactsPanel({ facts }: TransactionFactsPanelProps) {
  if (!facts) return null;

  const groups: { title: string; rows: { key: string; primary: string; secondary: React.ReactNode }[] }[] = [
    {
      title: 'Payments',
      rows: facts.payments.map((payment) => ({
        key: payment.paymentId,
        primary: payment.paymentId,
        secondary: (
          <>
            {humanizeEnum(payment.status)} · <MoneyDisplay money={payment.amount} /> ·{' '}
            {payment.processorReference ?? 'no processor ref'}
            {payment.avsResult || payment.cvvResult ? (
              <> · AVS {payment.avsResult ?? '-'} / CVV {payment.cvvResult ?? '-'}</>
            ) : null}
            {payment.capturedAt ? (
              <>
                {' '}
                · captured <TimestampDisplay value={payment.capturedAt} />
              </>
            ) : null}
          </>
        ),
      })),
    },
    {
      title: 'Orders',
      rows: facts.orders.map((order) => ({
        key: order.orderId,
        primary: order.orderId,
        secondary: (
          <>
            {humanizeEnum(order.status)} · <MoneyDisplay money={order.total} /> ·{' '}
            {order.lines.length} line{order.lines.length === 1 ? '' : 's'}
            {order.shippingAddress ? <> · {order.shippingAddress}</> : null}
          </>
        ),
      })),
    },
    {
      title: 'Shipments',
      rows: facts.shipments.map((shipment) => ({
        key: shipment.shipmentId,
        primary: shipment.shipmentId,
        secondary: (
          <>
            {shipment.carrier} · {shipment.trackingNumber} · {humanizeEnum(shipment.status)}
            {shipment.dispatchedAt ? (
              <>
                {' '}
                · dispatched <TimestampDisplay value={shipment.dispatchedAt} />
              </>
            ) : null}
          </>
        ),
      })),
    },
    {
      title: 'Deliveries',
      rows: facts.deliveries.map((delivery) => ({
        key: delivery.deliveryId,
        primary: delivery.deliveryId,
        secondary: (
          <>
            {humanizeEnum(delivery.status)}
            {delivery.signedBy ? <> · signed by {delivery.signedBy}</> : null}
            {delivery.proofType ? <> · {humanizeEnum(delivery.proofType)}</> : null}
            {delivery.deliveredAt ? (
              <>
                {' '}
                · <TimestampDisplay value={delivery.deliveredAt} />
              </>
            ) : null}
          </>
        ),
      })),
    },
    {
      title: 'Refunds',
      rows: facts.refunds.map((refund) => ({
        key: refund.refundId,
        primary: refund.refundId,
        secondary: (
          <>
            {humanizeEnum(refund.status)} · <MoneyDisplay money={refund.amount} />
            {refund.processedAt ? (
              <>
                {' '}
                · processed <TimestampDisplay value={refund.processedAt} />
              </>
            ) : null}
          </>
        ),
      })),
    },
    {
      title: 'Communications',
      rows: facts.communications.map((communication) => ({
        key: communication.communicationId,
        primary: communication.communicationId,
        secondary: (
          <>
            {humanizeEnum(communication.channel)} · {humanizeEnum(communication.direction)}
            {communication.subject ? <> · {communication.subject}</> : null} ·{' '}
            <TimestampDisplay value={communication.occurredAt} />
          </>
        ),
      })),
    },
  ].filter((group) => group.rows.length > 0);

  if (groups.length === 0) return null;

  const totalRows = groups.reduce((sum, group) => sum + group.rows.length, 0);

  return (
    <details className="group rounded-lg border border-border bg-card">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-2 px-4 py-2.5 text-sm font-medium">
        <span>
          Linked entities
          <span className="ml-2 text-xs font-normal text-muted-foreground">
            {totalRows} record{totalRows === 1 ? '' : 's'} across {groups.length} group
            {groups.length === 1 ? '' : 's'}
          </span>
        </span>
        <ChevronDown className="size-4 text-muted-foreground transition-transform group-open:rotate-180" aria-hidden />
      </summary>

      <div className="grid gap-4 border-t border-border p-4 sm:grid-cols-2 xl:grid-cols-3">
        {groups.map((group) => (
          <section key={group.title} aria-label={group.title} className="min-w-0">
            <h3 className="pb-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {group.title}
            </h3>
            <ul className="space-y-1.5">
              {group.rows.map((row) => (
                <li key={row.key} className="min-w-0 text-sm">
                  <span className="mono-id block truncate text-foreground">{row.primary}</span>
                  <span className="block text-xs text-muted-foreground">{row.secondary}</span>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </details>
  );
}
