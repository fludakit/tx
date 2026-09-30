package io.github.fludakit.tx.it.service;

import io.github.fludakit.tx.support.TransactionSynchronizationManager;

import jakarta.transaction.Transactional;

/**
 * Base class carrying a class-level {@code @Transactional(REQUIRED)}. Because
 * {@code @Transactional} is {@code @Inherited}, subclasses inherit the binding.
 */
@Transactional
public abstract class AbstractTxService {

    public boolean active() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
