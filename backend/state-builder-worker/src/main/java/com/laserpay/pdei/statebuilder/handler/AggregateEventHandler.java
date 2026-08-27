package com.laserpay.pdei.statebuilder.handler;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;

import java.util.Set;

/**
 * Projects the events of one aggregate family into PostgreSQL.
 *
 * <p>One implementation per aggregate - payment, order, shipment, refund, communication, dispute,
 * evidence - registered as a Spring bean and indexed by {@link StateBuilderDispatcher} on
 * {@link #handles()}. Adding an aggregate is a new bean; there is no switch statement to extend.
 *
 * <h2>What every implementation must guarantee</h2>
 *
 * <ol>
 *   <li><strong>Idempotent.</strong> Handling the same event twice must leave the database in the
 *       same state as handling it once. The listener's {@code processed_events} claim is the first
 *       line of defence; the per-row watermark is the second, and it is the one that survives a
 *       {@code processed_events} prune or a deliberate replay.</li>
 *   <li><strong>Out-of-order safe.</strong> Consult
 *       {@link com.laserpay.pdei.statebuilder.projection.ProjectionWatermark#shouldApply} before
 *       mutating a row and return without writing when it says no.</li>
 *   <li><strong>Replay safe.</strong> Rollups are recomputed from child rows rather than
 *       incremented, so re-running history converges instead of doubling.</li>
 *   <li><strong>Foreign keys satisfied.</strong> Use
 *       {@link com.laserpay.pdei.statebuilder.projection.ReferenceData} to create the parent rows a
 *       cross-aggregate event may arrive before.</li>
 * </ol>
 *
 * <p>Handlers run inside the listener's transaction, so a failure rolls back both the projection
 * write and the idempotency claim and the event is redelivered.
 */
public interface AggregateEventHandler {

    /** The canonical event types this handler owns. Two handlers may not claim the same type. */
    Set<EventType> handles();

    /** Applies one event to the projections. Called only for types in {@link #handles()}. */
    void handle(CanonicalEvent event);

    /** Name used in logs and metrics. */
    default String name() {
        return getClass().getSimpleName();
    }
}
