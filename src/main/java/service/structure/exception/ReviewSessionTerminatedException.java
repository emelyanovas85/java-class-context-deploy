package service.structure.exception;

/** Сессия явно терминирована или прерван in-flight build. → HTTP 410 */
public class ReviewSessionTerminatedException extends RuntimeException {

    public ReviewSessionTerminatedException(String sessionId) {
        super("Review session terminated: " + sessionId);
    }
}
