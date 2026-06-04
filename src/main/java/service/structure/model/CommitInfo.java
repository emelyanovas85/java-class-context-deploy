package service.structure.model;

/**
 * Краткая информация о коммите.
 *
 * @param sha         SHA коммита
 * @param title       первая строка сообщения
 * @param authorName  имя автора
 * @param authorEmail email автора
 * @param date        дата коммита
 */
public record CommitInfo(
        String sha,
        String title,
        String authorName,
        String authorEmail,
        String date
) {}
