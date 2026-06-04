package service.structure.dependency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import service.structure.parser.JavaStructureParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Извлекает полные имена Java-классов из {@code *-sources.jar} на диске
 * и читает исходный код отдельного класса по qualified name.
 *
 * <p>Для каждого {@code .java}-файла в jar индексируются <b>все</b> top-level типы
 * (в т.ч. package-private «соседи» в том же файле, что и public-класс).
 *
 * <p>Вложенные классы в индекс не попадают — только top-level.
 * При запросе по имени вложенного класса (e.g. {@code userInterface.ComplexTable.Row})
 * {@link #extractSourceFile} автоматически ищет файл родительского класса.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DependencyClassNameExtractor {

    private final JavaStructureParser structureParser;

    /** jarPath → (qualified name → zip entry path) */
    private final Map<Path, Map<String, String>> jarTypeIndexCache = new ConcurrentHashMap<>();

    /**
     * Читает jar с диска и возвращает множество qualified names всех top-level классов.
     *
     * @param jarPath  путь к jar-файлу на диске
     * @param depLabel метка зависимости для логов
     * @return множество qualified names
     */
    public Set<String> extractClassNames(Path jarPath, String depLabel) {
        return new HashSet<>(getOrBuildJarTypeIndex(jarPath, depLabel).keySet());
    }

    /**
     * Читает jar с диска и возвращает содержимое {@code .java}-файла
     * для указанного qualified name.
     *
     * <p>Сначала ищет тип в полном индексе top-level типов jar (включая package-private
     * «соседей»). Затем — по пути {@code QualifiedName.java} с подъёмом по outer-классам
     * для nested-типов.
     *
     * @param jarPath       путь к jar-файлу на диске
     * @param qualifiedName например {@code org.springframework.web.bind.annotation.RestController}
     *                      или {@code userInterface.ComplexTable.Row}
     * @return содержимое .java файла или {@code Optional.empty()} если не найдено
     */
    public Optional<String> extractSourceFile(Path jarPath, String qualifiedName) {
        Map<String, String> typeIndex = getOrBuildJarTypeIndex(jarPath, "lookup");
        String entryPath = typeIndex.get(qualifiedName);
        if (entryPath != null) {
            return readEntry(jarPath, entryPath);
        }

        String candidate = qualifiedName;
        while (!candidate.isEmpty()) {
            String targetEntry = candidate.replace('.', '/') + ".java";
            Optional<String> result = readEntry(jarPath, targetEntry);
            if (result.isPresent()) {
                if (!candidate.equals(qualifiedName)) {
                    log.debug("Resolved nested class {} via outer class file {}", qualifiedName, targetEntry);
                }
                return result;
            }
            int lastDot = candidate.lastIndexOf('.');
            if (lastDot < 0) break;
            candidate = candidate.substring(0, lastDot);
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, String> getOrBuildJarTypeIndex(Path jarPath, String depLabel) {
        return jarTypeIndexCache.computeIfAbsent(jarPath, path -> buildJarTypeIndex(path, depLabel));
    }

    private Map<String, String> buildJarTypeIndex(Path jarPath, String depLabel) {
        Map<String, String> index = new HashMap<>();
        try (ZipInputStream zip = openZip(jarPath)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".java")) continue;
                if (name.endsWith("module-info.java") || name.endsWith("package-info.java")) continue;

                String content = new String(zip.readAllBytes());
                indexTopLevelTypesInSource(content, name, index);
            }
        } catch (IOException e) {
            log.warn("Failed to read sources.jar for {}: {}", depLabel, e.getMessage());
        }
        log.info("Indexed {} top-level types from sources.jar of {}", index.size(), depLabel);
        return index;
    }

    private void indexTopLevelTypesInSource(String content, String entryPath, Map<String, String> index) {
        String packageName = packageFromEntryPath(entryPath);
        for (String simpleName : structureParser.topLevelTypeSimpleNames(content)) {
            String qName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            index.putIfAbsent(qName, entryPath);
        }
    }

    private static String packageFromEntryPath(String entryPath) {
        int slash = entryPath.lastIndexOf('/');
        if (slash < 0) return "";
        return entryPath.substring(0, slash).replace('/', '.');
    }

    private Optional<String> readEntry(Path jarPath, String targetEntry) {
        try (ZipInputStream zip = openZip(jarPath)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals(targetEntry)) {
                    return Optional.of(new String(zip.readAllBytes()));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read sources.jar while looking for {}: {}", targetEntry, e.getMessage());
        }
        return Optional.empty();
    }

    private ZipInputStream openZip(Path jarPath) throws IOException {
        return new ZipInputStream(Files.newInputStream(jarPath));
    }
}
