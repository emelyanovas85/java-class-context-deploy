package service.structure.session;

import org.gitlab4j.api.models.Diff;
import service.structure.model.MergeRequestInfo;
import service.structure.model.PinnedRefs;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final int depth;
    private final PinnedRefs pinnedRefs;
    private final MergeRequestInfo mrInfo;
    private final List<Diff> diffs;
    private final Map<String, List<String>> mergedFileIndex;
    private final Instant expiresAt;
    private final ReviewSessionCancellation cancellation;
    private final AtomicReference<Map<String, Path>> dependencySources = new AtomicReference<>();

    /** Создаёт сессию с зафиксированным merged index и handle отмены. */
    public ReviewSession(
            String sessionId,
            String gitlabUrl,
            String projectId,
            String token,
            long mergeRequestIid,
            int depth,
            PinnedRefs pinnedRefs,
            MergeRequestInfo mrInfo,
            List<Diff> diffs,
            Map<String, List<String>> mergedFileIndex,
            Instant expiresAt,
            ReviewSessionCancellation cancellation) {
        this.sessionId = sessionId;
        this.gitlabUrl = gitlabUrl;
        this.projectId = projectId;
        this.token = token;
        this.mergeRequestIid = mergeRequestIid;
        this.depth = depth;
        this.pinnedRefs = pinnedRefs;
        this.mrInfo = mrInfo;
        this.diffs = List.copyOf(diffs);
        this.mergedFileIndex = mergedFileIndex;
        this.expiresAt = expiresAt;
        this.cancellation = cancellation;
    }

    public String sessionId() { return sessionId; }
    public String gitlabUrl() { return gitlabUrl; }
    public String projectId() { return projectId; }
    public String token() { return token; }
    public long mergeRequestIid() { return mergeRequestIid; }
    public int depth() { return depth; }
    public PinnedRefs pinnedRefs() { return pinnedRefs; }
    public String sourceSha() { return pinnedRefs.sourceSha(); }
    public String targetSha() { return pinnedRefs.targetSha(); }
    public String baseSha() { return pinnedRefs.baseSha(); }
    public MergeRequestInfo mrInfo() { return mrInfo; }
    public List<Diff> diffs() { return diffs; }
    public Map<String, List<String>> mergedFileIndex() { return mergedFileIndex; }
    public Instant expiresAt() { return expiresAt; }
    public ReviewSessionCancellation cancellation() { return cancellation; }

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
