package io.github.fludakit.tx.support;

import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionSavepoint;

/**
 * A recording {@link AbstractTransactionOperations} used by the unit tests: it counts every
 * resource primitive the template drives and can be told to fail at any of them.
 */
final class StubTransactionManager extends AbstractTransactionOperations<String> implements SavepointManager {

    static final String RESOURCE = "resource";
    static final String COMMIT_FAILURE = "commit failed";
    static final String ROLLBACK_FAILURE = "rollback failed";
    static final String BEGIN_FAILURE = "begin failed";

    int beginCount;
    int commitCount;
    int rollbackCount;
    int savepointCount;
    int releaseCount;
    int rollbackToCount;
    int suspendCount;
    int resumeCount;

    boolean failBegin;
    boolean failCommit;
    boolean failRollback;
    boolean supportsSavepoints = true;

    DefaultTransactionStatus<String> lastBegun;

    @Override
    protected void doBegin(DefaultTransactionStatus<String> status) {
        beginCount++;
        lastBegun = status;
        if (failBegin) {
            throw new TransactionException(BEGIN_FAILURE);
        }
        bindResource(status, RESOURCE);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus<String> status) {
        commitCount++;
        if (failCommit) {
            throw new TransactionException(COMMIT_FAILURE);
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus<String> status) {
        rollbackCount++;
        if (failRollback) {
            throw new TransactionException(ROLLBACK_FAILURE);
        }
    }

    @Override
    protected void doSuspend(DefaultTransactionStatus<String> status) {
        suspendCount++;
    }

    @Override
    protected void doResume(DefaultTransactionStatus<String> status) {
        resumeCount++;
    }

    @Override
    protected SavepointManager newSavepointManager(DefaultTransactionStatus<String> status) {
        return supportsSavepoints ? this : null;
    }

    @Override
    public TransactionSavepoint createSavepoint() {
        savepointCount++;
        String name = "sp" + savepointCount;
        return () -> name;
    }

    @Override
    public void rollbackToSavepoint(TransactionSavepoint savepoint) {
        rollbackToCount++;
    }

    @Override
    public void releaseSavepoint(TransactionSavepoint savepoint) {
        releaseCount++;
    }
}
