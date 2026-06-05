package service.structure.exception;

/** Сессия не найдена: неверный id, terminate или истёк TTL. → HTTP 404 */
public class ReviewSessionNotFoundException extends RuntimeException {

    public ReviewSessionNotFoundException(String sessionId) {
        super("Review session not found: " + sessionId);
    }
}
