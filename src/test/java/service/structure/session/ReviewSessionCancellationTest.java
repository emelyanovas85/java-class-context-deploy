package service.structure.session;

import org.junit.jupiter.api.Test;
import service.structure.exception.ReviewSessionTerminatedException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSessionCancellationTest {

    @Test
    void terminate_cancelsInFlightFuture() throws InterruptedException {
        ReviewSessionCancellation cancellation = new ReviewSessionCancellation("abc12345");
        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            CompletableFuture<Void> future = cancellation.supplyAsync(() -> {
                started.countDown();
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ran.set(true);
                return null;
            }, executor);

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            cancellation.terminate();

            assertThatThrownBy(future::join)
                    .hasCauseInstanceOf(ReviewSessionTerminatedException.class);
            assertThat(ran.get()).isFalse();
        } finally {
            block.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void throwIfTerminated_afterTerminate() {
        ReviewSessionCancellation cancellation = new ReviewSessionCancellation("xyz");
        cancellation.terminate();
        assertThatThrownBy(cancellation::throwIfTerminated)
                .isInstanceOf(ReviewSessionTerminatedException.class);
    }
}
