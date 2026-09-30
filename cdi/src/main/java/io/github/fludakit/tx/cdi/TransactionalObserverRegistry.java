package io.github.fludakit.tx.cdi;

import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of transactional CDI observers collected at startup.
 *
 * <p>Holds the vetoed {@code @Observes(during != IN_PROGRESS)} observers collected by
 * {@link TransactionalCdiExtension}. Each {@link TransactionEventReplay} queries this registry
 * to obtain the observers for each phase.</p>
 */
final class TransactionalObserverRegistry {

    private final Map<TransactionPhase, List<ObserverMethod<?>>> observers = new EnumMap<>(TransactionPhase.class);

    /**
     * Registers a transactional observer for a specific phase.
     */
    void register(TransactionPhase phase, ObserverMethod<?> observer) {
        observers.computeIfAbsent(phase, k -> new ArrayList<>()).add(observer);
    }

    /**
     * Returns all observers registered for the given phase.
     */
    List<ObserverMethod<?>> getObservers(TransactionPhase phase) {
        return observers.getOrDefault(phase, List.of());
    }
}
