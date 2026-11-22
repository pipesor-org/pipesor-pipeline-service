package org.pipesor.pipeline;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records every capture scope sync and delegates to the real HTTP adapter (Ingestion is a closed
 * loopback port in integration tests, so the real outcome is {@code UNAVAILABLE}). It can instead throw
 * to prove that an unexpected failure never fails the committed request.
 */
public final class CaptureScopeSyncRecorder implements CaptureScopeSync {
    record Call(Scope scope, String correlationId, boolean insideTransaction, Outcome outcome) {}

    private final CaptureScopeSync real;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile boolean explode;
    /** When set, answers instead of the real adapter. */
    volatile java.util.function.Function<Scope, Result> stub;

    CaptureScopeSyncRecorder(CaptureScopeSync real) { this.real = real; }

    void reset() { calls.clear(); explode = false; stub = null; }

    @Override
    public Result sync(Scope scope, String correlationId) {
        boolean inside = TransactionSynchronizationManager.isActualTransactionActive();
        if (explode) {
            calls.add(new Call(scope, correlationId, inside, null));
            throw new IllegalStateException("simulated adapter defect");
        }
        var answer = stub;
        var result = answer != null ? answer.apply(scope) : real.sync(scope, correlationId);
        calls.add(new Call(scope, correlationId, inside, result.outcome()));
        return result;
    }
}
