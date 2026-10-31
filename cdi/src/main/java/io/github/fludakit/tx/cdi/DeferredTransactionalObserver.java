package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.support.TransactionStatusHolder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.ObserverMethod;

/**
 * The replacement observer registered for one vetoed transactional observer.
 *
 * <p>CDI matches events against {@link #getObservedType()} and {@link #getObservedQualifiers()},
 * which this class inherits from the original observer — so the container still does type
 * assignability and qualifier resolution, and a qualified observer only receives the events fired
 * with its qualifier. Delivery timing is the only thing that changes: declared
 * {@link TransactionPhase#IN_PROGRESS}, because the real phase is applied later by the
 * {@link TransactionEventDispatcher}.</p>
 *
 * <p>The original observer is called through {@link ObserverMethod#notify(Object)}, so its bean
 * instance is still resolved by the container at delivery time, exactly as for an in-progress
 * observer.</p>
 *
 * @param <T> the observed event type
 */
final class DeferredTransactionalObserver<T> implements ObserverMethod<T> {

    private final ObserverMethod<T> delegate;
    private final TransactionPhase phase;

    DeferredTransactionalObserver(ObserverMethod<T> delegate, TransactionPhase phase) {
        this.delegate = delegate;
        this.phase = phase;
    }

    @Override
    public Class<?> getBeanClass() {
        return delegate.getBeanClass();
    }

    /** The replacement stands in for the original observer, so it belongs to the same bean. */
    @Override
    public Bean<?> getDeclaringBean() {
        return delegate.getDeclaringBean();
    }

    @Override
    public Type getObservedType() {
        return delegate.getObservedType();
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        return delegate.getObservedQualifiers();
    }

    /**
     * Always capture, never filter at fire time: this observer is registered through
     * {@code AfterBeanDiscovery.addObserverMethod}, so it has no declaring bean the container could
     * evaluate {@code IF_EXISTS} against. The original observer is still called on its own bean
     * instance at delivery time.
     */
    @Override
    public Reception getReception() {
        return Reception.ALWAYS;
    }

    /** The container fires this observer immediately; the real phase is applied by the dispatcher. */
    @Override
    public TransactionPhase getTransactionPhase() {
        return TransactionPhase.IN_PROGRESS;
    }

    @Override
    public int getPriority() {
        return delegate.getPriority();
    }

    @Override
    public void notify(T event) {
        TransactionEventDispatcher dispatcher = TransactionEventDispatcher.of(TransactionStatusHolder.get());
        if (dispatcher != null) {
            dispatcher.defer(phase, delegate, event);
        }
    }

    @Override
    public String toString() {
        return "DeferredTransactionalObserver{phase=" + phase + ", delegate=" + delegate + '}';
    }
}
