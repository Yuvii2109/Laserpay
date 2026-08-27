package com.laserpay.pdei.simulator.emit;

import com.laserpay.pdei.simulator.world.SimEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live control surface of one running emission: stop, and the four stream-level chaos types.
 *
 * <p>Why a shared mutable object rather than parameters: the chaos engine acts on a run that is
 * <em>already in flight</em>. {@code POST /sim/v1/chaos {type: DUPLICATE_EVENT, count: 20}} has
 * to affect the next twenty events of a run started minutes ago, which means the emitter must
 * read its instructions from somewhere the HTTP thread can write to. Every field is an atomic and
 * the recent-event buffer is explicitly synchronised, so the HTTP thread and the emitter thread
 * can touch this concurrently without a lock discipline anyone has to remember.
 *
 * <p>Budgets are consumed, not toggled: {@code duplicateBudget = 20} means "duplicate the next
 * twenty events, then stop", which is what makes an injection a bounded, observable experiment
 * instead of a mode the operator has to remember to switch off.
 */
public class EmissionControl {

    private final String runId;
    private final int recentBufferSize;

    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger duplicateBudget = new AtomicInteger();
    private final AtomicInteger dropBudget = new AtomicInteger();
    private final AtomicInteger outOfOrderBudget = new AtomicInteger();
    private final AtomicInteger delayBudget = new AtomicInteger();
    private final AtomicLong delayMillis = new AtomicLong();

    private final AtomicLong duplicatesInjected = new AtomicLong();
    private final AtomicLong dropsInjected = new AtomicLong();
    private final AtomicLong reordersInjected = new AtomicLong();
    private final AtomicLong delaysInjected = new AtomicLong();

    /** Recently emitted events, newest first, so chaos can re-publish real traffic. */
    private final Deque<SimEvent> recent = new ArrayDeque<>();

    public EmissionControl(String runId, int recentBufferSize) {
        this.runId = runId;
        this.recentBufferSize = Math.max(1, recentBufferSize);
    }

    public String runId() {
        return runId;
    }

    // -------------------------------------------------------------------------------------
    // Stop
    // -------------------------------------------------------------------------------------

    /** Requests a cooperative stop; the emitter checks this between events. */
    public void stop() {
        stopped.set(true);
    }

    public boolean isStopped() {
        return stopped.get();
    }

    // -------------------------------------------------------------------------------------
    // Chaos budgets, set by ChaosEngine and consumed by EventEmitter
    // -------------------------------------------------------------------------------------

    public void addDuplicateBudget(int count) {
        duplicateBudget.addAndGet(Math.max(0, count));
    }

    public void addDropBudget(int count) {
        dropBudget.addAndGet(Math.max(0, count));
    }

    public void addOutOfOrderBudget(int count) {
        outOfOrderBudget.addAndGet(Math.max(0, count));
    }

    /** Delay the next {@code count} events by {@code millis} each. */
    public void addDelay(int count, long millis) {
        delayMillis.set(Math.max(0L, millis));
        delayBudget.addAndGet(Math.max(0, count));
    }

    /** @return true when this event should be published twice */
    public boolean consumeDuplicate() {
        if (consume(duplicateBudget)) {
            duplicatesInjected.incrementAndGet();
            return true;
        }
        return false;
    }

    /** @return true when this event should be silently dropped */
    public boolean consumeDrop() {
        if (consume(dropBudget)) {
            dropsInjected.incrementAndGet();
            return true;
        }
        return false;
    }

    /** @return true when this event should be held back and emitted after the following one */
    public boolean consumeOutOfOrder() {
        if (consume(outOfOrderBudget)) {
            reordersInjected.incrementAndGet();
            return true;
        }
        return false;
    }

    /** @return milliseconds to sleep before publishing this event, 0 for none */
    public long consumeDelayMillis() {
        if (consume(delayBudget)) {
            delaysInjected.incrementAndGet();
            return delayMillis.get();
        }
        return 0L;
    }

    // -------------------------------------------------------------------------------------
    // Recent traffic
    // -------------------------------------------------------------------------------------

    public void remember(SimEvent event) {
        synchronized (recent) {
            recent.addFirst(event);
            while (recent.size() > recentBufferSize) {
                recent.removeLast();
            }
        }
    }

    /** Most recent events, newest first. */
    public List<SimEvent> recent(int limit) {
        synchronized (recent) {
            List<SimEvent> snapshot = new ArrayList<>(Math.min(limit, recent.size()));
            for (SimEvent event : recent) {
                if (snapshot.size() >= limit) {
                    break;
                }
                snapshot.add(event);
            }
            return List.copyOf(snapshot);
        }
    }

    public boolean hasRecent() {
        synchronized (recent) {
            return !recent.isEmpty();
        }
    }

    // -------------------------------------------------------------------------------------
    // Counters for the chaos injection record
    // -------------------------------------------------------------------------------------

    public long duplicatesInjected() {
        return duplicatesInjected.get();
    }

    public long dropsInjected() {
        return dropsInjected.get();
    }

    public long reordersInjected() {
        return reordersInjected.get();
    }

    public long delaysInjected() {
        return delaysInjected.get();
    }

    /** Atomically decrements a budget when it is positive. */
    private static boolean consume(AtomicInteger budget) {
        while (true) {
            int current = budget.get();
            if (current <= 0) {
                return false;
            }
            if (budget.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
}
