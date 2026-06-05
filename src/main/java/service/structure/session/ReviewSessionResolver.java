package service.structure.session;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Точка входа для work-контроллеров: резолв активной сессии по {@code sessionId}.
 */
@Component
@RequiredArgsConstructor
public class ReviewSessionResolver {

    private final ReviewSessionService reviewSessionService;

    /**
     * Активная сессия с готовым merged file index.
     * Блокирует до завершения фонового построения индекса при create.
     *
     * @throws service.structure.exception.ReviewSessionNotFoundException сессия не найдена или TTL
     * @throws service.structure.exception.ReviewSessionTerminatedException сессия терминирована
     * @throws service.structure.exception.ReviewSessionIndexBuildException ошибка построения индекса
     */
    public ReviewSession requireActive(String sessionId) {
        ReviewSession session = reviewSessionService.requirePresent(sessionId);
        session.awaitIndexReady();
        return session;
    }
}
