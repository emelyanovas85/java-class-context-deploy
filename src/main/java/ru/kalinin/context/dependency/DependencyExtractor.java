package ru.kalinin.context.dependency;

import java.util.List;

/**
 * Абстракция извлечения зависимостей из файла сборки.
 *
 * <p>Реализации:
 * <ul>
 *   <li>{@link GradleDependencyExtractor} — для {@code *.gradle}</li>
 *   <li>{@link MavenDependencyExtractor} — для {@code pom.xml} (заготовка)</li>
 * </ul>
 */
public interface DependencyExtractor {

    /**
     * Проверяет, умеет ли данная реализация обрабатывать файл с таким именем.
     *
     * @param fileName имя файла (без пути), например {@code build.gradle} или {@code pom.xml}
     */
    boolean supports(String fileName);

    /**
     * Извлекает зависимости из содержимого файла сборки.
     *
     * <p>Возвращает только зависимости с явно указанной версией;
     * BOM-managed зависимости (без версии) в результат не включаются.
     *
     * @param fileContent содержимое файла
     * @return список координат зависимостей
     */
    List<DependencyCoordinate> extract(String fileContent);
}
