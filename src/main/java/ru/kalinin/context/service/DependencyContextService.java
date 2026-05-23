package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.kalinin.context.dependency.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Оркестрирует этап сбора контекста зависимостей.
 *
 * <h3>Алгоритм</h3>
 * <ol>
 *   <li>Найти файлы сборки (*.gradle / pom.xml) через уже готовый fileIndex.</li>
 *   <li>Прочитать их содержимое.</li>
 *   <li>Найти все Artifactory repo URL из Gradle-файлов.</li>
 *   <li>Извлечь зависимости с явными версиями через подходящий {@link DependencyExtractor}.</li>
 *   <li>Обойти граф зависимостей волновым BFS: каждая волна (фронт не обработанных координат)
 *       обрабатывается <b>параллельно</b> через {@code ioExecutor}:
 *       <ul>
 *         <li>скачать / взять с диска {@code sources.jar} → проиндексировать классы;</li>
 *         <li>скачать / взять с диска {@code .module} → извлечь api-зависимости.</li>
 *       </ul>
 *       Результаты волны собираются, из них формируется следующая волна.
 *       {@code visited} защищает от циклов.</li>
 * </ol>
 *
 * <p>Результат — {@code Map<String, Path>}: qualified name класса → путь к jar на диске.
 * Байты jar в памяти не удерживаются.
 *
 * <h3>Thread safety</h3>
 * <p>{@link ArtifactorySourcesLoader} и {@link DependencyClassNameExtractor} должны быть
 * thread-safe (файловый кэш на диске, синхронизированный через атомарные операции ФС или
 * аналогичный механизм — см. реализации).
 */
@Slf4j
@Service
public class DependencyContextService {

    private final GitLabService gitLabService;
    private final List<DependencyExtractor> extractors;
    private final ArtifactorySourcesLoader sourcesLoader;
    private final DependencyClassNameExtractor classNameExtractor;
    private final ExecutorService ioExecutor;

    public DependencyContextService(GitLabService gitLabService,
                                    List<DependencyExtractor> extractors,
                                    ArtifactorySourcesLoader sourcesLoader,
                                    DependencyClassNameExtractor classNameExtractor,
                                    @Qualifier("ioExecutor") ExecutorService ioExecutor) {
        this.gitLabService = gitLabService;
        this.extractors = extractors;
        this.sourcesLoader = sourcesLoader;
        this.classNameExtractor = classNameExtractor;
        this.ioExecutor = ioExecutor;
    }

    // ── вспомогательный record: результат обработки одной зависимости ────────

    private record DepResult(
            Map<String, Path> classMap,        // qName → jarPath
            List<DependencyCoordinate> apiDeps // транзитивные api-зависимости
    ) {}

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

            extractors.stream()
                    .filter(e -> e.supports(fileName))
                    .findFirst()
                    .ifPresentOrElse(
                            extractor -> {
                                try {
                                    List<DependencyCoordinate> deps = extractor.extract(entry.getValue());
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

        // 5. Волновой BFS: каждая волна обрабатывается параллельно ─────────────
        //
        //  wave 0: directDeps  ──▶  [jar+module каждый параллельно]
        //                           ↓ api-deps из .module → wave 1
        //  wave 1: apiDeps     ──▶  [jar+module каждый параллельно]
        //                           ↓ api-deps → wave 2  ...  до опустошения
        //
        Map<String, Path> dependencySources = new ConcurrentHashMap<>();
        Set<String> visited = ConcurrentHashMap.newKeySet();

        List<DependencyCoordinate> currentWave = directDeps.stream()
                .filter(d -> visited.add(d.toString()))
                .toList();

        while (!currentWave.isEmpty()) {
            log.debug("BFS wave: {} deps to process in parallel", currentWave.size());

            // Запускаем все зависимости волны параллельно
            List<CompletableFuture<DepResult>> futures = currentWave.stream()
                    .map(dep -> CompletableFuture.supplyAsync(
                            () -> processDep(dep, artifactoryUrls), ioExecutor))
                    .toList();

            // Собираем результаты и формируем следующую волну
            List<DependencyCoordinate> nextWave = new ArrayList<>();
            for (CompletableFuture<DepResult> future : futures) {
                DepResult result;
                try {
                    result = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while downloading dependency", e);
                } catch (ExecutionException e) {
                    log.warn("Failed to process dependency", e.getCause());
                    continue;
                }
                dependencySources.putAll(result.classMap());
                for (DependencyCoordinate apiDep : result.apiDeps()) {
                    if (visited.add(apiDep.toString())) {
                        nextWave.add(apiDep);
                    }
                }
            }

            currentWave = nextWave;
        }

        log.info("Total dependency classes indexed (incl. transitive api): {}", dependencySources.size());
        return Collections.unmodifiableMap(dependencySources);
    }

    // ── обработка одной зависимости (вызывается из пула потоков) ─────────────

    private DepResult processDep(DependencyCoordinate dep, List<String> artifactoryUrls) {
        String depKey = dep.toString();
        Map<String, Path> classMap = new HashMap<>();

        // скачать sources.jar и проиндексировать классы
        sourcesLoader.resolveSourcesJar(artifactoryUrls, dep).ifPresent(jarPath -> {
            Set<String> classNames = classNameExtractor.extractClassNames(jarPath, depKey);
            classNames.forEach(qName -> classMap.put(qName, jarPath));
            log.debug("Indexed {} classes from {}", classNames.size(), dep);
        });

        // попытаться найти .module и раскрыть api-зависимости
        List<DependencyCoordinate> apiDeps = new ArrayList<>();
        sourcesLoader.resolveModuleFile(artifactoryUrls, dep).ifPresent(moduleFile -> {
            List<DependencyCoordinate> parsed = sourcesLoader.parseApiDependencies(moduleFile, dep);
            apiDeps.addAll(parsed);
        });

        return new DepResult(classMap, apiDeps);
    }
}
