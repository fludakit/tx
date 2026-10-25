package io.github.fludakit.tx.cdi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-transaction buffer of CDI event payloads fired while the transaction was in progress.
 *
 * <p>Bound to the {@link io.github.fludakit.tx.support.TransactionContext} under
 * {@code TransactionEventBuffer.class} by {@link TransactionEventReplay} when the interceptor
 * starts the transaction — a CDI-only concern that lives in this module, not in core.</p>
 */
final class TransactionEventBuffer {

    private final List<Object> events = new ArrayList<>();

    void add(Object event) {
        events.add(event);
    }

    /** Unmodifiable view of the buffered payloads, in firing order. */
    List<Object> events() {
        return Collections.unmodifiableList(events);
    }
}
