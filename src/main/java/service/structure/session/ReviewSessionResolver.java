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

    /** @throws service.structure.exception.ReviewSessionNotFoundException сессия не найдена или TTL */
    public ReviewSession requireActive(String sessionId) {
        return reviewSessionService.requirePresent(sessionId);
    }
}
