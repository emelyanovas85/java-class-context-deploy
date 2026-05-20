package ru.kalinin.context.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Извлекает полные имена Java-классов из {@code *-sources.jar} на диске
 * и читает исходный код отдельного класса по qualified name.
 *
 * <p>Qualified name вычисляется из пути к {@code .java}-файлу внутри jar:
 * {@code com/example/Foo.java} → {@code com.example.Foo}.
 *
 * <p>Вложенные классы в индекс не попадают — только top-level.
 * Они всегда находятся в файле своего top-level-контейнера.
 */
@Slf4j
@Component
public class DependencyClassNameExtractor {

    /**
     * Читает jar с диска и возвращает множество qualified names всех top-level классов.
     *
     * @param jarPath  путь к jar-файлу на диске
     * @param depLabel метка зависимости для логов
     * @return множество qualified names
     */
    public Set<String> extractClassNames(Path jarPath, String depLabel) {
        Set<String> classNames = new HashSet<>();
        try (ZipInputStream zip = openZip(jarPath)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".java")) {
                    String qualifiedName = toQualifiedName(name);
                    if (qualifiedName != null) {
                        classNames.add(qualifiedName);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read sources.jar for {}: {}", depLabel, e.getMessage());
        }
        log.info("Extracted {} class names from sources.jar of {}", classNames.size(), depLabel);
        return classNames;
    }

    /**
     * Читает jar с диска и возвращает содержимое {@code .java}-файла
     * для указанного qualified name.
     *
     * @param jarPath       путь к jar-файлу на диске
     * @param qualifiedName например {@code org.springframework.web.bind.annotation.RestController}
     * @return содержимое .java файла или {@code Optional.empty()} если не найдено
     */
    public Optional<String> extractSourceFile(Path jarPath, String qualifiedName) {
        String targetEntry = qualifiedName.replace('.', '/') + ".java";
        try (ZipInputStream zip = openZip(jarPath)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals(targetEntry)) {
                    return Optional.of(new String(zip.readAllBytes()));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read sources.jar while looking for {}: {}", qualifiedName, e.getMessage());
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ZipInputStream openZip(Path jarPath) throws IOException {
        InputStream is = Files.newInputStream(jarPath);
        return new ZipInputStream(is);
    }

    /**
     * Преобразует путь внутри zip в qualified name.
     * Возвращает {@code null} для module-info и package-info.
     */
    private static String toQualifiedName(String entryName) {
        String qName = entryName
                .replace('/', '.')
                .replace('\\', '.')
                .replaceAll("\\.java$", "");
        if (qName.endsWith("module-info") || qName.endsWith("package-info")) {
            return null;
        }
        return qName;
    }
}
