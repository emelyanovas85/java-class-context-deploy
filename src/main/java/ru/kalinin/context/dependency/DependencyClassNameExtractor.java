package ru.kalinin.context.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Извлекает полные имена Java-классов из байт {@code *-sources.jar}
 * и читает исходные файлы по qualified name.
 *
 * <p>Qualified name вычисляется из пути к {@code .java}-файлу:
 * {@code com/example/Foo.java} → {@code com.example.Foo}.
 *
 * <p>Вложенные классы в индекс не попадают — только top-level.
 * Этого достаточно для фильтрации и резолвинга: вложенный класс
 * всегда находится в файле своего top-level-контейнера.
 */
@Slf4j
@Component
public class DependencyClassNameExtractor {

    /**
     * Разбирает bytes sources.jar и возвращает карту:
     * qualified name → содержимое {@code .java}-файла.
     *
     * @param jarBytes байты jar-файла
     * @param depLabel метка зависимости для логов
     * @return карта qualified name → исходный код
     */
    public Map<String, String> extractSources(byte[] jarBytes, String depLabel) {
        Map<String, String> sources = new HashMap<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (!entry.isDirectory() && entryName.endsWith(".java")) {
                    String qualifiedName = entryName
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceAll("\\.java$", "");
                    if (qualifiedName.endsWith("module-info")
                            || qualifiedName.endsWith("package-info")) {
                        continue;
                    }
                    String content = new String(zip.readAllBytes());
                    sources.put(qualifiedName, content);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read sources.jar for {}: {}", depLabel, e.getMessage());
        }

        log.info("Extracted {} source entries from sources.jar of {}", sources.size(), depLabel);
        return sources;
    }

    /**
     * Извлекает исходный код одного {@code .java}-файла из jar по qualified name.
     *
     * <p>Удобен когда нужен только один файл без построения полной карты.
     *
     * @param jarBytes      байты jar-файла
     * @param qualifiedName например {@code org.springframework.web.bind.annotation.RestController}
     * @return содержимое .java файла или {@code Optional.empty()} если не найдено
     */
    public Optional<String> extractSourceFile(byte[] jarBytes, String qualifiedName) {
        String targetEntry = qualifiedName.replace('.', '/') + ".java";

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
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

    /**
     * @deprecated Используйте {@link #extractSources(byte[], String)} и получайте keySet().
     */
    @Deprecated(forRemoval = true)
    public java.util.Set<String> extractClassNames(byte[] jarBytes, String depLabel) {
        return extractSources(jarBytes, depLabel).keySet();
    }
}
