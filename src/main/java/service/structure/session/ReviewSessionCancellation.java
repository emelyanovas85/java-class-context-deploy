package service.structure.session;

import service.structure.exception.ReviewSessionTerminatedException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Сигнал отмены сессии: флаг terminate и регистрация in-flight {@link CompletableFuture}.
 */
public final class ReviewSessionCancellation {

    private final String sessionId;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<CompletableFuture<?>> inFlight = new CopyOnWriteArrayList<>();

    /** @param sessionId uid сессии для сообщений об ошибке */
    public ReviewSessionCancellation(String sessionId) {
        this.sessionId = sessionId;
    }

    /** Бросает {@link ReviewSessionTerminatedException}, если сессия терминирована. */
    public void throwIfTerminated() {
        if (terminated.get()) {
            throw new ReviewSessionTerminatedException(sessionId);
        }
    }

    /** Запускает задачу с проверкой terminate до и после старта. */
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> task, Executor executor) {
        throwIfTerminated();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            throwIfTerminated();
            return task.get();
        }, executor);
        inFlight.add(future);
        future.whenComplete((ignored, ex) -> inFlight.remove(future));
        return future;
    }

    /** Отменяет все зарегистрированные futures и помечает сессию терминированной. */
    public void terminate() {
        terminated.set(true);
        for (CompletableFuture<?> future : inFlight) {
            future.cancel(true);
        }
        inFlight.clear();
    }

    public boolean isTerminated() {
        return terminated.get();
    }

    public String sessionId() {
        return sessionId;
    }
}
