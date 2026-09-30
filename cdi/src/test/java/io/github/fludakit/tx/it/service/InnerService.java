package io.github.fludakit.tx.it.service;

import io.github.fludakit.tx.support.TransactionContextHolder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Inner bean whose methods report whether a transaction is active inside them, so the propagation
 * tests can observe join/suspend behavior. Lives in a separate bean because CDI interceptors do not
 * apply to self-invocation.
 */
@ApplicationScoped
public class InnerService {

    private static boolean isTransactionActive() {
        var ctx = TransactionContextHolder.get();
        return ctx != null && ctx.isActualTransactionActive();
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public boolean required() {
        return isTransactionActive();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean requiresNew() {
        return isTransactionActive();
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean supports() {
        return isTransactionActive();
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean notSupported() {
        return isTransactionActive();
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void mandatory() {
    }

    @Transactional(Transactional.TxType.NEVER)
    public void never() {
    }
}
