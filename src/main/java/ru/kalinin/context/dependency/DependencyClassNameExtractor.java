package ru.kalinin.context.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Извлекает полные имена Java-классов из байт {@code *-sources.jar}.
 *
 * <p>Источниками служат {@code .java}-файлы внутри jar.
 * Qualified name вычисляется из пути к файлу:
 * {@code com/example/Foo.java} → {@code com.example.Foo}.
 *
 * <p>Примечание: используется имя файла (simple name), не анализ AST,
 * поэтому вложенные классы в индекс не попадают — только top-level.
 * Этого достаточно для задачи фильтрации «знаем ли мы этот тип».
 */
@Slf4j
@Component
public class DependencyClassNameExtractor {

    /**
     * Разбирает bytes sources.jar и возвращает множество qualified names классов.
     *
     * @param jarBytes байты jar-файла
     * @param depLabel метка зависимости для логов
     * @return множество qualified names, например {@code org.springframework.web.bind.annotation.RestController}
     */
    public Set<String> extractClassNames(byte[] jarBytes, String depLabel) {
        Set<String> classNames = new HashSet<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".java")) {
                    String qualifiedName = name
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceAll("\\.java$", "");
                    // Отбрасываем module-info и package-info
                    if (!qualifiedName.endsWith("module-info")
                            && !qualifiedName.endsWith("package-info")) {
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
}
