package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.kalinin.context.dependency.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Оркестрирует этап сбора контекста зависимостей.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>Найти файлы сборки (*.gradle / pom.xml) через уже готовый fileIndex.</li>
 *   <li>Прочитать их содержимое.</li>
 *   <li>Найти все Artifactory repo URL из Gradle-файлов.</li>
 *   <li>Извлечь зависимости с явными версиями через подходящий {@link DependencyExtractor}.</li>
 *   <li>Для каждой зависимости получить путь к sources.jar на диске
 *       (скачать если нет) и проиндексировать классы.</li>
 *   <li>Рекурсивно обработать транзитивные api-зависимости:
 *       для каждой зависимости ищем .module файл в Artifactory,
 *       извлекаем блок apiElements и добавляем новые координаты в очередь.
 *       Используется {@code visited} для защиты от циклов.</li>
 * </ol>
 *
 * <p>Результат — {@code Map<String, Path>}: qualified name класса → путь к jar на диске.
 * Байты jar в памяти не удерживаются.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyContextService {

    private final GitLabService gitLabService;
    private final List<DependencyExtractor> extractors;
    private final ArtifactorySourcesLoader sourcesLoader;
    private final DependencyClassNameExtractor classNameExtractor;

    /**
     * Собирает карту «qualified name → путь к jar на диске» для всех классов из зависимостей,
     * включая транзитивные api-зависимости.
     *
     * <p>Jar-файлы и .module-файлы сохраняются в {@code /artifacts} и при повторном запросе
     * берутся с диска без сетевого вызова.
     *
     * @param gitlabUrl URL GitLab-инстанса
     * @param token     токен доступа
     * @param projectId идентификатор проекта
     * @param branch    ветка (source branch из MR)
     * @param fileIndex мёрженный файловый индекс (имя → пути), построенный в buildContext()
     * @return карта qualified name → путь к jar (может быть пустой)
     */
    public Map<String, Path> collectDependencySources(
            String gitlabUrl, String token, String projectId, String branch,
            Map<String, List<String>> fileIndex) {

        log.info("Collecting dependency sources for project={} branch={}", projectId, branch);

        // 1. Найти файлы сборки через готовый индекс
        List<String> buildFilePaths = gitLabService.findBuildFiles(fileIndex);
        if (buildFilePaths.isEmpty()) {
            log.warn("No build files found in project={} branch={}", projectId, branch);
            return Map.of();
        }
        log.info("Found {} build file(s): {}", buildFilePaths.size(), buildFilePaths);

        // 2. Прочитать содержимое найденных файлов сборки
        Map<String, String> buildFileContents = new LinkedHashMap<>();
        for (String path : buildFilePaths) {
            gitLabService.readRawFileContent(gitlabUrl, token, projectId, branch, path)
                    .ifPresent(content -> buildFileContents.put(path, content));
        }

        // 3. Найти все Artifactory repo URL из Gradle-файлов
        List<String> gradleContents = buildFileContents.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".gradle") || e.getKey().endsWith(".gradle.kts"))
                .map(Map.Entry::getValue)
                .toList();

        List<String> artifactoryUrls = sourcesLoader.detectArtifactoryUrls(gradleContents);
        if (artifactoryUrls.isEmpty()) {
            log.warn("Artifactory URLs not found — skipping dependency sources download");
            return Map.of();
        }

        // 4. Извлечь прямые зависимости из файлов сборки
        List<DependencyCoordinate> directDeps = new ArrayList<>();
        for (Map.Entry<String, String> entry : buildFileContents.entrySet()) {
            String fileName = entry.getKey().contains("/")
                    ? entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1)
                    : entry.getKey();
            String content = entry.getValue();

            extractors.stream()
                    .filter(e -> e.supports(fileName))
                    .findFirst()
                    .ifPresentOrElse(
                            extractor -> {
                                try {
                                    List<DependencyCoordinate> deps = extractor.extract(content);
                                    directDeps.addAll(deps);
                                    log.debug("Extracted {} deps from {}", deps.size(), entry.getKey());
                                } catch (UnsupportedOperationException ex) {
                                    log.info("Extractor for {} not yet implemented, skipping", fileName);
                                }
                            },
                            () -> log.debug("No extractor for file: {}", fileName)
                    );
        }

        if (directDeps.isEmpty()) {
            log.info("No versioned dependencies found");
            return Map.of();
        }
        log.info("Direct versioned dependencies found: {}", directDeps.size());

        // 5. Рекурсивно обходим все зависимости (прямые + api-транзитивные)
        Map<String, Path> dependencySources = new HashMap<>();
        // Ключ visited: "groupId:artifactId:version", чтобы не обрабатывать одну зависимость дважды
        Set<String> visited = new HashSet<>();
        Deque<DependencyCoordinate> queue = new ArrayDeque<>(directDeps);

        while (!queue.isEmpty()) {
            DependencyCoordinate dep = queue.poll();
            String depKey = dep.toString();
            if (!visited.add(depKey)) {
                continue; // уже обработано
            }

            // скачать sources.jar и проиндексировать классы
            sourcesLoader.resolveSourcesJar(artifactoryUrls, dep).ifPresent(jarPath -> {
                Set<String> classNames = classNameExtractor.extractClassNames(jarPath, depKey);
                classNames.forEach(qName -> dependencySources.put(qName, jarPath));
                log.debug("Indexed {} classes from {}", classNames.size(), dep);
            });

            // попытаться найти .module и раскрыть api-зависимости
            sourcesLoader.resolveModuleFile(artifactoryUrls, dep).ifPresent(moduleFile -> {
                List<DependencyCoordinate> apiDeps =
                        sourcesLoader.parseApiDependencies(moduleFile, dep);
                for (DependencyCoordinate apiDep : apiDeps) {
                    if (!visited.contains(apiDep.toString())) {
                        log.debug("Queuing transitive api dep from {}: {}", dep, apiDep);
                        queue.add(apiDep);
                    }
                }
            });
        }

        log.info("Total dependency classes indexed (incl. transitive api): {}", dependencySources.size());
        return Collections.unmodifiableMap(dependencySources);
    }
}
