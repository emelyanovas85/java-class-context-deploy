package service.structure.model;

import java.util.List;

/**
 * Ответ {@code POST /api/source-file}: совпадения по каждому запрошенному имени.
 */
public record FileSourceResponse(List<NameResult> names) {

    /** Результат поиска по одному имени из запроса. */
    public record NameResult(String name, List<FileMatch> files) {}

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
