package service.structure.session;

import org.junit.jupiter.api.Test;
import service.structure.exception.ReviewSessionIndexBuildException;
import service.structure.exception.ReviewSessionTerminatedException;
import service.structure.model.MergeRequestInfo;
import service.structure.model.PinnedRefs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSessionIndexTest {

    @Test
    void awaitIndexReady_blocksUntilFutureCompletes() throws InterruptedException {
        CountDownLatch built = new CountDownLatch(1);
        CompletableFuture<Map<String, List<String>>> future = new CompletableFuture<>();
        ReviewSession session = testSession(future);

        Thread waiter = new Thread(() -> {
            session.awaitIndexReady();
            built.countDown();
        });
        waiter.start();

        assertThat(built.await(500, TimeUnit.MILLISECONDS)).isFalse();

        future.complete(Map.of("Foo.java", List.of("src/main/java/Foo.java")));
        waiter.join(2_000);

        assertThat(built.await(100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(session.mergedFileIndex()).containsKey("Foo.java");
    }

    @Test
    void awaitIndexReady_throwsWhenBuildFails() {
        CompletableFuture<Map<String, List<String>>> future = new CompletableFuture<>();
        ReviewSession session = testSession(future);
        future.completeExceptionally(new RuntimeException("gitlab tree timeout"));

        assertThatThrownBy(session::awaitIndexReady)
                .isInstanceOf(ReviewSessionIndexBuildException.class);
    }

    @Test
    void awaitIndexReady_throwsWhenTerminatedDuringBuild() {
        CompletableFuture<Map<String, List<String>>> future = new CompletableFuture<>();
        ReviewSessionCancellation cancellation = new ReviewSessionCancellation("sess9999");
        ReviewSession session = new ReviewSession(
                "sess9999", "https://gitlab.com", "p", "token", 1L,
                new PinnedRefs("h", "t", "b"),
                new MergeRequestInfo(1L, "t", "opened", "s", "t", "u",
                        List.of(), List.of(), List.of()),
                List.of(), future, Instant.now().plusSeconds(3600), cancellation);

        cancellation.terminate();
        future.cancel(true);

        assertThatThrownBy(session::awaitIndexReady)
                .isInstanceOf(ReviewSessionTerminatedException.class);
    }

    private static ReviewSession testSession(CompletableFuture<Map<String, List<String>>> future) {
        PinnedRefs refs = new PinnedRefs("head", "start", "base");
        MergeRequestInfo mr = new MergeRequestInfo(
                1L, "t", "opened", "s", "t", "u",
                List.of(), List.of(), List.of(), refs);
        return new ReviewSession(
                "sess1234", "https://gitlab.com", "p", "token", 1L,
                refs, mr, List.of(), future, Instant.now().plusSeconds(3600),
                new ReviewSessionCancellation("sess1234"));
    }
}
