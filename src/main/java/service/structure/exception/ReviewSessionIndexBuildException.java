package service.structure.exception;

/** Ошибка фонового построения merged file index. → HTTP 503 */
public class ReviewSessionIndexBuildException extends RuntimeException {

    public ReviewSessionIndexBuildException(String sessionId, Throwable cause) {
        super("Failed to build file index for session " + sessionId + ": "
                + (cause != null ? cause.getMessage() : "unknown"), cause);
    }
}
