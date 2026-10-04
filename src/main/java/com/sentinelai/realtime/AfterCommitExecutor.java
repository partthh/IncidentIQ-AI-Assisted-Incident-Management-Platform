package com.sentinelai.realtime;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work only after the current transaction has committed.
 *
 * <p>This is the guarantee the specification asks for: persist state <em>then</em>
 * publish. Publishing inside the transaction would let a client observe an
 * incident that a rollback then erased; publishing on a plain scheduler would
 * need a second mechanism to avoid the same race. Registering an after-commit
 * hook is the smallest correct answer for a single-node deployment.
 *
 * <p>See {@code docs/architecture.md} for why this becomes a transactional
 * outbox the moment a message broker is introduced.
 */
public final class AfterCommitExecutor {

    private AfterCommitExecutor() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            // No transaction in progress (for example a scheduled worker): the
            // caller's own commit already happened, so run immediately.
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
