package service.structure.session;

import org.gitlab4j.api.models.Diff;
import service.structure.exception.ReviewSessionIndexBuildException;
import service.structure.exception.ReviewSessionTerminatedException;
import service.structure.model.MergeRequestInfo;
import service.structure.model.PinnedRefs;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory снимок MR для одной сессии ревью: credentials, pin SHA, frozen diff и артефакты.
 */
public final class ReviewSession {

    private final String sessionId;
    private final String gitlabUrl;
    private final String projectId;
    private final String token;
    private final long mergeRequestIid;
    private final PinnedRefs pinnedRefs;
    private final MergeRequestInfo mrInfo;
    private final List<Diff> diffs;
    private final CompletableFuture<Map<String, List<String>>> mergedFileIndexFuture;
    private final Instant expiresAt;
    private final ReviewSessionCancellation cancellation;
    private final AtomicReference<Map<String, Path>> dependencySources = new AtomicReference<>();

    /**
     * @param mergedFileIndexFuture фоновое построение merged index; work-операции делают join
     */
    public ReviewSession(
            String sessionId,
            String gitlabUrl,
            String projectId,
            String token,
            long mergeRequestIid,
            PinnedRefs pinnedRefs,
            MergeRequestInfo mrInfo,
            List<Diff> diffs,
            CompletableFuture<Map<String, List<String>>> mergedFileIndexFuture,
            Instant expiresAt,
            ReviewSessionCancellation cancellation) {
        this.sessionId = sessionId;
        this.gitlabUrl = gitlabUrl;
        this.projectId = projectId;
        this.token = token;
        this.mergeRequestIid = mergeRequestIid;
        this.pinnedRefs = pinnedRefs;
        this.mrInfo = mrInfo;
        this.diffs = List.copyOf(diffs);
        this.mergedFileIndexFuture = mergedFileIndexFuture;
        this.expiresAt = expiresAt;
        this.cancellation = cancellation;
    }

    public String sessionId() { return sessionId; }
    public String gitlabUrl() { return gitlabUrl; }
    public String projectId() { return projectId; }
    public String token() { return token; }
    public long mergeRequestIid() { return mergeRequestIid; }
    public PinnedRefs pinnedRefs() { return pinnedRefs; }
    public String sourceSha() { return pinnedRefs.sourceSha(); }
    public String targetSha() { return pinnedRefs.targetSha(); }
    public String baseSha() { return pinnedRefs.baseSha(); }
    public MergeRequestInfo mrInfo() { return mrInfo; }
    public List<Diff> diffs() { return diffs; }
    public Instant expiresAt() { return expiresAt; }
    public ReviewSessionCancellation cancellation() { return cancellation; }

    /** {@code true}, если merged index уже построен (или завершился с ошибкой). */
    public boolean isIndexReady() {
        return mergedFileIndexFuture.isDone();
    }

    /** Блокирует до готовности merged index. Бросает 410/503 при terminate или ошибке build. */
    public void awaitIndexReady() {
        awaitMergedFileIndex();
    }

    /** Merged file index; блокирует до завершения фонового построения. */
    public Map<String, List<String>> mergedFileIndex() {
        return awaitMergedFileIndex();
    }

    private Map<String, List<String>> awaitMergedFileIndex() {
        cancellation.throwIfTerminated();
        try {
            return mergedFileIndexFuture.join();
        } catch (CompletionException e) {
            throw unwrapIndexFutureException(e);
        }
    }

    private RuntimeException unwrapIndexFutureException(CompletionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof ReviewSessionTerminatedException terminated) {
            throw terminated;
        }
        if (cause instanceof CancellationException || cancellation.isTerminated()) {
            throw new ReviewSessionTerminatedException(sessionId);
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new ReviewSessionIndexBuildException(sessionId, cause);
    }

    /** {@code true}, если dependencySources уже собирали (lazy, при первом depth &gt; 0 или source-file). */
    public boolean isDependencySourcesLoaded() {
        return dependencySources.get() != null;
    }

    /** Lazy-кэш dependencySources; {@code null}, если ещё не собирали. */
    public Map<String, Path> dependencySourcesOrNull() {
        return dependencySources.get();
    }

    /** Сохраняет карту зависимостей (один раз, compare-and-set). */
    public void setDependencySources(Map<String, Path> sources) {
        dependencySources.compareAndSet(null, Map.copyOf(sources));
    }

    /** Карта зависимостей или пустая map, если ещё не инициализирована. */
    public Map<String, Path> dependencySourcesOrEmpty() {
        Map<String, Path> cached = dependencySources.get();
        return cached != null ? cached : Map.of();
    }

    /** Ключ эксклюзивности: {@code gitlabUrl::projectId::mrIid}. */
    public String mrKey() {
        return gitlabUrl + "::" + projectId + "::" + mergeRequestIid;
    }
}
