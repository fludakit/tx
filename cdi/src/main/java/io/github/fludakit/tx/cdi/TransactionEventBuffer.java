package io.github.fludakit.tx.cdi;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-transaction buffer of CDI events fired while the transaction was in progress, recorded
 * per capture group.
 *
 * <p>Bound to the {@link io.github.fludakit.tx.support.TransactionContext} under
 * {@code TransactionEventBuffer.class} by {@link TransactionEventReplay} when the interceptor
 * starts the transaction — a CDI-only concern that lives in this module, not in core.</p>
 */
final class TransactionEventBuffer {

    /**
     * Identity of one group of vetoed observers: the observed type and the qualifier set they
     * were declared with. CDI notifies the capture observer registered for a group exactly for
     * the events that group's observers would receive, so the key is both the capture filter and
     * the replay lookup key.
     */
    record CaptureKey(Type observedType, Set<Annotation> qualifiers) {
    }

    private final Map<CaptureKey, List<Object>> events = new LinkedHashMap<>();

    void add(CaptureKey key, Object payload) {
        events.computeIfAbsent(key, k -> new ArrayList<>()).add(payload);
    }

    /** Buffered payloads for one capture group, in firing order. */
    List<Object> events(CaptureKey key) {
        return Collections.unmodifiableList(events.getOrDefault(key, List.of()));
    }
}
