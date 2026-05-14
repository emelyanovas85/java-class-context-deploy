package ru.kalinin.context.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает зависимости из Gradle-файлов (*.gradle, *.gradle.kts).
 *
 * <p>Поддерживаемые нотации:
 * <ul>
 *   <li>Строковая:  {@code implementation 'group:artifact:version'}</li>
 *   <li>Строковая с двойными кавычками: {@code implementation "group:artifact:version"}</li>
 *   <li>Map-нотация: {@code implementation group: 'com.example', name: 'foo', version: '1.0'}</li>
 * </ul>
 *
 * <p>Зависимости без явной версии (BOM-managed) в результат не включаются.
 */
@Slf4j
@Component
public class GradleDependencyExtractor implements DependencyExtractor {

    /**
     * Строковая нотация: configuration 'group:artifact:version' или "group:artifact:version".
     * Версия обязательна — строки без третьего сегмента игнорируются.
     */
    private static final Pattern STRING_NOTATION = Pattern.compile(
            "(?:implementation|api|compileOnly|runtimeOnly|annotationProcessor"
            + "|testImplementation|testCompileOnly|testRuntimeOnly|testAnnotationProcessor"
            + "|provided|compile|runtime)"
            + "\\s+[\"']([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)[\"']",
            Pattern.MULTILINE);

    /**
     * Map-нотация:
     * {@code implementation group: 'com.example', name: 'foo', version: '1.2.3'}
     */
    private static final Pattern MAP_NOTATION = Pattern.compile(
            "(?:implementation|api|compileOnly|runtimeOnly|annotationProcessor"
            + "|testImplementation|testCompileOnly|testRuntimeOnly|testAnnotationProcessor"
            + "|provided|compile|runtime)"
            + "\\s+group:\\s*[\"']([A-Za-z0-9_.\\-]+)[\"']\\s*,\\s*"
            + "name:\\s*[\"']([A-Za-z0-9_.\\-]+)[\"']\\s*,\\s*"
            + "version:\\s*[\"']([A-Za-z0-9_.\\-]+)[\"']",
            Pattern.MULTILINE);

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts");
    }

    @Override
    public List<DependencyCoordinate> extract(String fileContent) {
        List<DependencyCoordinate> result = new ArrayList<>();

        // Строковая нотация
        Matcher m1 = STRING_NOTATION.matcher(fileContent);
        while (m1.find()) {
            result.add(new DependencyCoordinate(m1.group(1), m1.group(2), m1.group(3)));
            log.debug("Gradle string dep: {}:{}:{}", m1.group(1), m1.group(2), m1.group(3));
        }

        // Map-нотация
        Matcher m2 = MAP_NOTATION.matcher(fileContent);
        while (m2.find()) {
            result.add(new DependencyCoordinate(m2.group(1), m2.group(2), m2.group(3)));
            log.debug("Gradle map dep: {}:{}:{}", m2.group(1), m2.group(2), m2.group(3));
        }

        log.info("GradleDependencyExtractor: found {} versioned dependencies", result.size());
        return result;
    }
}
