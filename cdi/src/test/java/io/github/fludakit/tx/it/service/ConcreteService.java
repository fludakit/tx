package io.github.fludakit.tx.it.service;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.support.TransactionStatusHolder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Inherits the class-level {@code @Transactional(REQUIRED)} from {@link AbstractTxService} and
 * overrides it on one method with {@code NOT_SUPPORTED} to exercise method-level precedence.
 */
@ApplicationScoped
public class ConcreteService extends AbstractTxService {

    @Override
    public boolean active() {
        return super.active();
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean notSupported() {
        TransactionStatus<?> status = TransactionStatusHolder.get();
        return status != null && status.isActualTransactionActive();
    }
}
