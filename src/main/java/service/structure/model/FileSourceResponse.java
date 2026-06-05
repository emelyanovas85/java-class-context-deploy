package service.structure.model;

import java.util.List;

/**
 * Ответ {@code POST /api/source-file}: все найденные совпадения в repo и dependencies.
 *
 * @param name  исходное имя из запроса
 * @param files совпадения (может быть пустым списком)
 */
public record FileSourceResponse(String name, List<FileMatch> files) {

    /**
     * Один найденный исходник.
     *
     * @param origin         {@code repo} или {@code dependency}
     * @param path           путь в репозитории или синтетический путь в jar
     * @param qualifiedName  полное имя класса
     * @param module         {@code src/main}, {@code src/test} или Maven-координаты
     * @param content        полный текст файла
     */
    public record FileMatch(
            String origin,
            String path,
            String qualifiedName,
            String module,
            String content
    ) {}
}
