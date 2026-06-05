package service.structure.session;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import service.structure.exception.MergeRequestAlreadyMergedException;
import service.structure.exception.ReviewSessionNotFoundException;
import service.structure.model.ContextRequest;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Жизненный цикл сессий: create, terminate, resolve, lazy-сбор dependencySources.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewSessionService {

    private static final Set<String> ANALYZABLE_STATES = Set.of("opened", "locked");
    private static final String UID_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final GitLabService gitLabService;
    private final DependencyContextService dependencyContextService;
    private final Cache<String, ReviewSession> reviewSessionCache;
    private final ConcurrentHashMap<String, String> activeSessionByMrKey;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.review-session.ttl-minutes:120}")
    private int ttlMinutes;

    @Value("${app.review-session.uid-length:8}")
    private int uidLength;

    /**
     * Создаёт сессию с pin SHA и merged index.
     * Повторный вызов для того же MR терминирует предыдущую сессию.
     */
    public CreateSessionResponse create(ContextRequest request) {
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
        Map<String, List<String>> mergedIndex = gitLabService.buildMergedFileIndex(
                request.gitlabUrl(), request.token(), request.projectId(),
                pinned.targetSha(), snapshot.mrInfo().diffs());

        String sessionId = generateUniqueSessionId();
        Instant expiresAt = Instant.now().plusSeconds(ttlMinutes * 60L);
        ReviewSessionCancellation cancellation = new ReviewSessionCancellation(sessionId);

        ReviewSession session = new ReviewSession(
                sessionId,
                request.gitlabUrl(),
                request.projectId(),
                request.token(),
                request.mergeRequestIid(),
                request.depth(),
                pinned,
                snapshot.mrInfo(),
                snapshot.mrInfo().diffs(),
                mergedIndex,
                expiresAt,
                cancellation
        );

        reviewSessionCache.put(sessionId, session);
        activeSessionByMrKey.put(mrKey, sessionId);

        log.info("Created review session {} for MR !{} (source={}, target={})",
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

    /** Собирает dependencySources один раз и кэширует в сессии (при {@code depth > 0}). */
    public Map<String, Path> getOrBuildDependencySources(ReviewSession session) {
        Map<String, Path> cached = session.dependencySourcesOrNull();
        if (cached != null) {
            return cached;
        }
        if (session.depth() == 0) {
            session.setDependencySources(Map.of());
            return Map.of();
        }
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
