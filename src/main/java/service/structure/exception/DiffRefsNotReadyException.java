package service.structure.exception;

/** GitLab ещё не заполнил {@code diff_refs} для MR. → HTTP 503 */
public class DiffRefsNotReadyException extends RuntimeException {

    public DiffRefsNotReadyException(long mergeRequestIid) {
        super("diff_refs not ready for merge request !" + mergeRequestIid);
    }
}
