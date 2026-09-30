package io.github.fludakit.tx.it.service;

import io.github.fludakit.tx.support.TransactionContextHolder;

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
        var ctx = TransactionContextHolder.get();
        return ctx != null && ctx.isActualTransactionActive();
    }
}
