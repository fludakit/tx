package io.github.fludakit.tx.cdi;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Per-transaction buffer of CDI events fired while the transaction was in progress, recorded
 * with the qualifiers the capturing observer was registered for.
 *
 * <p>Bound to the {@link io.github.fludakit.tx.support.TransactionContext} under
 * {@code TransactionEventBuffer.class} by {@link TransactionEventReplay} when the interceptor
 * starts the transaction — a CDI-only concern that lives in this module, not in core.</p>
 */
final class TransactionEventBuffer {

    /** One buffered event: the payload and the qualifiers it was captured for. */
    record BufferedEvent(Object payload, Set<Annotation> qualifiers) {
    }

    private final List<BufferedEvent> events = new ArrayList<>();

    void add(Object payload, Set<Annotation> qualifiers) {
        events.add(new BufferedEvent(payload, qualifiers));
    }

    /** Unmodifiable view of the buffered events, in firing order. */
    List<BufferedEvent> events() {
        return Collections.unmodifiableList(events);
    }
}
