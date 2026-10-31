package io.github.fludakit.tx.it.service;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.support.TransactionStatusHolder;

import jakarta.transaction.Transactional;

/**
 * Base class carrying a class-level {@code @Transactional(REQUIRED)}. Because
 * {@code @Transactional} is {@code @Inherited}, subclasses inherit the binding.
 */
@Transactional
public abstract class AbstractTxService {

    public boolean active() {
        TransactionStatus<?> status = TransactionStatusHolder.get();
        return status != null && status.isActualTransactionActive();
    }
}
