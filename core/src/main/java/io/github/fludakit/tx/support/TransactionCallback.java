package io.github.fludakit.tx.support;

public interface TransactionCallback {
    default void beforeCommit(boolean readOnly) {
    }

    default void beforeCompletion() {
    }

    default void afterCommit() {
    }

    default void afterCompletion(CompletionStatus status) {
    }

    enum CompletionStatus {
        COMMITTED, ROLLED_BACK, UNKNOWN
    }
}
