package ru.kalinin.context.exception;

/**
 * Бросается, если мёрж-реквест уже смерджен и анализ source/target невозможен.
 *
 * @param mergeRequestIid iid мёрж-реквеста
 * @param state           фактическое состояние MR из GitLab API
 */
public class MergeRequestAlreadyMergedException extends RuntimeException {

    public MergeRequestAlreadyMergedException(long mergeRequestIid, String state) {
        super("Merge request !" + mergeRequestIid + " is already '" + state
                + "'. Only open merge requests can be analyzed.");
    }
}
