package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.ObserverMethod;

/**
 * Defers the CDI events fired inside a transaction to the transactional observers that asked for a
 * completion phase, and delivers them when the transaction reaches that phase.
 *
 * <p>Because the transactions here are resource-local, the CDI container cannot know when they
 * complete: it only drives {@code @Observes(during = ...)} observers for JTA transactions. So
 * {@link TransactionalCdiExtension} vetoes those observers at deployment and registers a
 * {@link DeferredTransactionalObserver} for each of them. When such an event is fired the synthetic
 * observer hands the payload to this dispatcher, which holds it until the matching callback:</p>
 *
 * <table border="1">
 *   <caption>Phase delivery</caption>
 *   <tr><th>CDI phase</th><th>Delivered from</th><th>When</th></tr>
 *   <tr><td>{@code BEFORE_COMPLETION}</td><td>{@link #beforeCompletion()}</td>
 *       <td>before commit or rollback</td></tr>
 *   <tr><td>{@code AFTER_SUCCESS}</td><td>{@link #afterCommit()}</td><td>after a commit</td></tr>
 *   <tr><td>{@code AFTER_FAILURE}</td><td>{@link #afterCompletion(Status)}</td>
 *       <td>after a rollback</td></tr>
 *   <tr><td>{@code AFTER_COMPLETION}</td><td>{@link #afterCompletion(Status)}</td>
 *       <td>after either outcome</td></tr>
 * </table>
 *
 * <h2>Installation</h2>
 * <p>One dispatcher exists per transaction, installed lazily by the first event fired in it and
 * bound to the status as an attribute. That keeps event delivery independent of how the
 * transaction was started: the declarative interceptor and a programmatic
 * {@link io.github.fludakit.tx.TransactionManager#execute} boundary both get it. The binding is
 * atomic and joined/nested statuses delegate attributes to the transaction that is actually
 * running, so participants share one dispatcher and every event is delivered exactly once — at the
 * real boundary.</p>
 *
 * <p>An event fired with no actual transaction active is dropped: a transactional observer must
 * not fire without a transaction. An exception thrown by an observer is logged and does not stop
 * the other observers or the transaction.</p>
 */
final class TransactionEventDispatcher implements TransactionSynchronization {

    private static final Logger LOG = Logger.getLogger(TransactionEventDispatcher.class.getName());

    private final List<DeferredNotification> notifications = new CopyOnWriteArrayList<>();

    private TransactionEventDispatcher() {
    }

    /**
     * The dispatcher of the transaction the status belongs to, installing one when this is the
     * first event.
     *
     * @param status the current status, or {@code null} outside a transaction scope
     * @return the dispatcher, or {@code null} when no actual transaction is active
     */
    static TransactionEventDispatcher of(TransactionStatus<?> status) {
        if (status == null || !status.isActualTransactionActive() || status.isCompleted()) {
            return null;
        }
        TransactionEventDispatcher existing = status.getAttribute(TransactionEventDispatcher.class);
        if (existing != null) {
            return existing;
        }
        TransactionEventDispatcher created = new TransactionEventDispatcher();
        TransactionEventDispatcher bound = status.putAttributeIfAbsent(TransactionEventDispatcher.class, created);
        if (bound != null) {
            // another thread fired the first event of this transaction
            return bound;
        }
        status.registerSynchronization(created);
        return created;
    }

    /** Defers one event for one observer until its phase is reached. */
    void defer(TransactionPhase phase, ObserverMethod<?> observer, Object payload) {
        notifications.add(new DeferredNotification(phase, observer, payload));
    }

    @Override
    public void beforeCompletion() {
        dispatch(TransactionPhase.BEFORE_COMPLETION);
    }

    @Override
    public void afterCommit() {
        dispatch(TransactionPhase.AFTER_SUCCESS);
    }

    @Override
    public void afterCompletion(Status status) {
        if (status == Status.ROLLED_BACK) {
            dispatch(TransactionPhase.AFTER_FAILURE);
        }
        dispatch(TransactionPhase.AFTER_COMPLETION);
    }

    private void dispatch(TransactionPhase phase) {
        for (DeferredNotification notification : notifications) {
            if (notification.phase() == phase) {
                notification.deliver();
            }
        }
    }

    /** One buffered event, bound to the observer and the phase it is waiting for. */
    private record DeferredNotification(TransactionPhase phase, ObserverMethod<?> observer, Object payload) {

        @SuppressWarnings({"rawtypes", "unchecked"})
        void deliver() {
            try {
                ((ObserverMethod) observer).notify(payload);
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "Transactional observer failed to handle event " + payload, ex);
            }
        }
    }
}
