package service.structure.session;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import service.structure.exception.MergeRequestAlreadyMergedException;
import service.structure.exception.ReviewSessionNotFoundException;
import service.structure.model.CreateSessionRequest;
import service.structure.model.CreateSessionResponse;
import service.structure.model.PinnedRefs;
import service.structure.service.DependencyContextService;
import service.structure.service.GitLabService;
import service.structure.service.GitLabService.MergeRequestSnapshot;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Жизненный цикл сессий: create, terminate, resolve, lazy-сбор dependencySources.
 */
@Slf4j
@Service
public class ReviewSessionService {

    private static final Set<String> ANALYZABLE_STATES = Set.of("opened", "locked");
    private static final String UID_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final GitLabService gitLabService;
    private final DependencyContextService dependencyContextService;
    private final Cache<String, ReviewSession> reviewSessionCache;
    private final ConcurrentHashMap<String, String> activeSessionByMrKey;
    private final ExecutorService ioExecutor;
    private final SecureRandom random = new SecureRandom();

    public ReviewSessionService(
            GitLabService gitLabService,
            DependencyContextService dependencyContextService,
            Cache<String, ReviewSession> reviewSessionCache,
            ConcurrentHashMap<String, String> activeSessionByMrKey,
            @Qualifier("ioExecutor") ExecutorService ioExecutor) {
        this.gitLabService = gitLabService;
        this.dependencyContextService = dependencyContextService;
        this.reviewSessionCache = reviewSessionCache;
        this.activeSessionByMrKey = activeSessionByMrKey;
        this.ioExecutor = ioExecutor;
    }

    @Value("${app.review-session.ttl-minutes:120}")
    private int ttlMinutes;

    @Value("${app.review-session.uid-length:8}")
    private int uidLength;

    /**
     * Создаёт сессию с pin SHA. Merged file index строится в фоне — create возвращается сразу.
     * Повторный вызов для того же MR терминирует предыдущую сессию.
     */
    public CreateSessionResponse create(CreateSessionRequest request) {
        MergeRequestSnapshot snapshot = gitLabService.getMergeRequestSnapshot(
                request.gitlabUrl(), request.token(),
                request.projectId(), request.mergeRequestIid());

        if (!ANALYZABLE_STATES.contains(snapshot.mrInfo().state())) {
            throw new MergeRequestAlreadyMergedException(
                    request.mergeRequestIid(), snapshot.mrInfo().state());
        }

        String mrKey = request.gitlabUrl() + "::" + request.projectId()
                + "::" + request.mergeRequestIid();
        String previousId = activeSessionByMrKey.get(mrKey);
        if (previousId != null) {
            terminate(previousId);
        }

        PinnedRefs pinned = snapshot.pinnedRefs();
        String sessionId = generateUniqueSessionId();
        Instant expiresAt = Instant.now().plusSeconds(ttlMinutes * 60L);
        ReviewSessionCancellation cancellation = new ReviewSessionCancellation(sessionId);

        CompletableFuture<Map<String, List<String>>> indexFuture = cancellation.supplyAsync(
                () -> gitLabService.buildMergedFileIndex(
                        request.gitlabUrl(), request.token(), request.projectId(),
                        pinned.targetSha(), snapshot.mrInfo().diffs()),
                ioExecutor);
        indexFuture.whenComplete((index, ex) -> {
            if (ex != null) {
                log.error("Failed to build merged file index for session {}", sessionId, ex);
            } else {
                log.info("Merged file index ready for session {}: {} filenames",
                        sessionId, index.size());
            }
        });

        ReviewSession session = new ReviewSession(
                sessionId,
                request.gitlabUrl(),
                request.projectId(),
                request.token(),
                request.mergeRequestIid(),
                pinned,
                snapshot.mrInfo(),
                snapshot.mrInfo().diffs(),
                indexFuture,
                expiresAt,
                cancellation
        );

        reviewSessionCache.put(sessionId, session);
        activeSessionByMrKey.put(mrKey, sessionId);

        log.info("Created review session {} for MR !{} (source={}, target={}); index building in background",
                sessionId, request.mergeRequestIid(),
                pinned.sourceSha(), pinned.targetSha());

        return new CreateSessionResponse(
                sessionId,
                pinned.sourceSha(),
                pinned.targetSha(),
                pinned.baseSha(),
                expiresAt
        );
    }

    /** Отменяет in-flight задачи и немедленно удаляет сессию из cache. */
    public void terminate(String sessionId) {
        ReviewSession session = reviewSessionCache.getIfPresent(sessionId);
        if (session != null) {
            session.cancellation().terminate();
            reviewSessionCache.invalidate(sessionId);
            activeSessionByMrKey.remove(session.mrKey(), sessionId);
            log.info("Terminated review session {}", sessionId);
        }
    }

    /**
     * Возвращает активную сессию или бросает {@link ReviewSessionNotFoundException} /
     * {@link service.structure.exception.ReviewSessionTerminatedException}.
     */
    public ReviewSession requirePresent(String sessionId) {
        ReviewSession session = reviewSessionCache.getIfPresent(sessionId);
        if (session == null) {
            throw new ReviewSessionNotFoundException(sessionId);
        }
        session.cancellation().throwIfTerminated();
        return session;
    }

    /** Собирает dependencySources для поиска в jar (например, {@code /api/source-file}). */
    public Map<String, Path> getOrBuildDependencySources(ReviewSession session) {
        return buildDependencySourcesIfNeeded(session);
    }

    /** Собирает dependencySources для построения контекста; при {@code depth == 0} — только кэш. */
    public Map<String, Path> getOrBuildDependencySources(ReviewSession session, int depth) {
        if (depth == 0) {
            Map<String, Path> cached = session.dependencySourcesOrNull();
            return cached != null ? cached : Map.of();
        }
        return buildDependencySourcesIfNeeded(session);
    }

    private Map<String, Path> buildDependencySourcesIfNeeded(ReviewSession session) {
        Map<String, Path> cached = session.dependencySourcesOrNull();
        if (cached != null) {
            return cached;
        }
        log.info("Loading dependencySources for session {} (first request with depth > 0 or source-file)",
                session.sessionId());
        Map<String, Path> built = dependencyContextService.collectDependencySources(
                session.gitlabUrl(), session.token(), session.projectId(),
                session.sourceSha(), session.mergedFileIndex());
        session.setDependencySources(built);
        return built;
    }

    private String generateUniqueSessionId() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String id = randomUid();
            if (reviewSessionCache.getIfPresent(id) == null) {
                return id;
            }
        }
        throw new IllegalStateException("Failed to generate unique session id");
    }

    private String randomUid() {
        StringBuilder sb = new StringBuilder(uidLength);
        for (int i = 0; i < uidLength; i++) {
            sb.append(UID_ALPHABET.charAt(random.nextInt(UID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
