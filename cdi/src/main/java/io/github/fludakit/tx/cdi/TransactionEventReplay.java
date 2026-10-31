package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;

import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CDI transaction synchronization that replays buffered events to transactional observers.
 *
 * <p>At each lifecycle point it queries the {@link TransactionalObserverRegistry} for observers
 * of the matching phase and replays the events buffered in this transaction's
 * {@link TransactionEventBuffer}.</p>
 *
 * <p>Installed by {@link TransactionalInterceptor} on every transaction it begins. Because a
 * joined or nested status registers its synchronizations on the running transaction, an event
 * fired inside any participant reaches the boundary that owns the transaction and is replayed
 * exactly once.</p>
 */
final class TransactionEventReplay implements TransactionSynchronization {

    private static final Logger LOG = Logger.getLogger(TransactionEventReplay.class.getName());

    private final TransactionalObserverRegistry registry;
    private final TransactionEventBuffer buffer;

    TransactionEventReplay(TransactionStatus<?> status, TransactionalObserverRegistry registry) {
        this.registry = registry;
        this.buffer = new TransactionEventBuffer();
        // the capture observer finds the buffer through the status's typed attributes
        status.putAttributeIfAbsent(TransactionEventBuffer.class, buffer);
    }

    @Override
    public void beforeCompletion() {
        notify(TransactionPhase.BEFORE_COMPLETION);
    }

    @Override
    public void afterCommit() {
        notify(TransactionPhase.AFTER_SUCCESS);
    }

    @Override
    public void afterCompletion(Status status) {
        if (status == Status.ROLLED_BACK) {
            notify(TransactionPhase.AFTER_FAILURE);
        }
        notify(TransactionPhase.AFTER_COMPLETION);
    }

    /**
     * Replays, to every observer of the phase, the events buffered for that observer's own
     * capture group. The capture observer for a group is notified by CDI exactly for the events
     * the group's observers are entitled to — type assignability and qualifier matching included
     * — so replaying the group's buffer reproduces CDI delivery without re-deriving qualifier
     * semantics from the event alone.
     */
    private void notify(TransactionPhase phase) {
        for (ObserverMethod<?> observer : registry.getObservers(phase)) {
            TransactionEventBuffer.CaptureKey key = new TransactionEventBuffer.CaptureKey(
                    observer.getObservedType(), observer.getObservedQualifiers());
            for (Object payload : buffer.events(key)) {
                notifySafely(observer, payload);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void notifySafely(ObserverMethod observer, Object payload) {
        try {
            observer.notify(payload);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Transactional observer failed to handle event", e);
        }
    }
}
