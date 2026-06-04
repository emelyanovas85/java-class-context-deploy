package service.structure.model;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Контексты top-level классов одного {@code .java}-файла.
 *
 * <p>Несколько package-private типов в одном файле (например {@code A} и {@code B}
 * в {@code A.java}) могут иметь разные {@link ClassContext#level()}, но попадают
 * в один {@code FileContext}.
 *
 * @param path    путь к файлу в репозитории или синтетический путь в sources.jar
 * @param module  источник: {@code "main"}, {@code "test"} или {@code groupId:artifactId:version}
 * @param level   минимальный уровень среди классов файла (для сортировки)
 * @param classes контексты классов этого файла
 */
public record FileContext(
        String path,
        String module,
        int level,
        List<ClassContext> classes
) {
    @Override
    public String toString() {
        return classes.stream()
                .sorted(Comparator.comparingInt(ClassContext::level).thenComparingInt(ClassContext::id))
                .map(ClassContext::toString)
                .collect(Collectors.joining("\n\n"));
    }
}
