package service.structure.session;

import org.junit.jupiter.api.Test;
import service.structure.exception.ReviewSessionTerminatedException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSessionCancellationTest {

    @Test
    void terminate_cancelsInFlightFuture() {
        ReviewSessionCancellation cancellation = new ReviewSessionCancellation("abc12345");
        AtomicBoolean ran = new AtomicBoolean(false);

        CompletableFuture<Void> future = cancellation.supplyAsync(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.set(true);
            return null;
        }, Executors.newVirtualThreadPerTaskExecutor());

        cancellation.terminate();

        assertThatThrownBy(future::join)
                .hasCauseInstanceOf(ReviewSessionTerminatedException.class);
        assertThat(ran.get()).isFalse();
    }

    @Test
    void throwIfTerminated_afterTerminate() {
        ReviewSessionCancellation cancellation = new ReviewSessionCancellation("xyz");
        cancellation.terminate();
        assertThatThrownBy(cancellation::throwIfTerminated)
                .isInstanceOf(ReviewSessionTerminatedException.class);
    }
}
