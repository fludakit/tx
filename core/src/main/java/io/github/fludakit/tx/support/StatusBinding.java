package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionStatus;

import java.util.Objects;

/**
 * A revocable handle to a bound {@link TransactionStatus}.
 *
 * <p>The indirection exists for one reason: an {@link InheritableThreadLocal} copies the
 * <em>reference</em> to the value when a thread is created, so replacing or removing the
 * thread-local in the parent does not reach a child thread that already inherited it. Closing
 * the shared binding does — every thread holding this handle sees "not bound" once the scope
 * that created it ends, which is what stops a transaction from outliving its boundary on a raw
 * thread.</p>
 *
 * <p>On JDK 25+ the status travels through a {@code ScopedValue} instead, where inheritance is
 * structured and scoped by construction; closing the binding there is redundant but keeps the
 * two implementations indistinguishable.</p>
 *
 * @param <T> the resource type of the bound transaction
 */
final class StatusBinding<T> {

    private final TransactionStatus<T> status;
    private volatile boolean closed;

    StatusBinding(TransactionStatus<T> status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    /** The bound status, or {@code null} once this binding closed. */
    TransactionStatus<T> get() {
        return closed ? null : status;
    }

    /** Revokes this binding for every thread that inherited it. */
    void close() {
        closed = true;
    }
}
