package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CDI transaction callback that replays buffered events to transactional observers.
 *
 * <p>This callback queries the {@link TransactionalObserverRegistry} for observers at each
 * phase and replays the events buffered in this transaction's {@link TransactionEventBuffer}
 * to matching observers.</p>
 *
 * <p>Registered on each {@code TransactionContext} created by the interceptor, this callback
 * automatically replays buffered CDI events at the matching phase when the core lifecycle
 * methods are invoked.</p>
 */
final class TransactionEventReplay implements TransactionCallback {

    private static final Logger LOG = Logger.getLogger(TransactionEventReplay.class.getName());

    private final TransactionalObserverRegistry registry;
    private final TransactionEventBuffer buffer;

    TransactionEventReplay(TransactionContext context, TransactionalObserverRegistry registry) {
        this.registry = registry;
        this.buffer = new TransactionEventBuffer();
        // the capture observer finds the buffer through the context's type-keyed resources
        context.bindResource(TransactionEventBuffer.class, buffer);
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
    public void afterCompletion(CompletionStatus status) {
        if (status == CompletionStatus.ROLLED_BACK) {
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
