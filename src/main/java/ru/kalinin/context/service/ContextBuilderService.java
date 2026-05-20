package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.kalinin.context.dependency.DependencyClassNameExtractor;
import ru.kalinin.context.exception.MergeRequestAlreadyMergedException;
import ru.kalinin.context.model.*;
import ru.kalinin.context.parser.JavaStructureParser;
import ru.kalinin.context.parser.StructureNodeMapper;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Основной сервис: строит многоуровневый контекст для мёрж-реквеста.
 *
 * <h3>Алгоритм</h3>
 * <ol>
 *   <li>Проверка состояния MR.</li>
 *   <li>Построение мёрженного файлового индекса.</li>
 *   <li>Сбор карты зависимостей: qualified name → путь к jar.</li>
 *   <li>Сборка wildcardResolver: при парсинге каждого файла передаётся лямбда,
 *       которая резолвит (simpleName, wildcardPackages) → qualified name,
 *       используя fileIndex и dependencySources.</li>
 *   <li>Уровень 0: изменённые файлы.</li>
 *   <li>Уровни 1..depth: зависимости, поиск по exact qualified name в repo и jar.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextBuilderService {

    private static final Set<String> ANALYZABLE_STATES = Set.of("opened", "locked");

    private final GitLabService gitLabService;
    private final JavaStructureParser structureParser;
    private final StructureNodeMapper nodeMapper;
    private final DependencyContextService dependencyContextService;
    private final DependencyClassNameExtractor classNameExtractor;

    public ContextResponse buildContext(ContextRequest request) {
        MergeRequestInfo mrInfo = gitLabService.getMergeRequestInfo(
                request.gitlabUrl(), request.token(),
                request.projectId(), request.mergeRequestIid());

        if (!ANALYZABLE_STATES.contains(mrInfo.state())) {
            throw new MergeRequestAlreadyMergedException(
                    request.mergeRequestIid(), mrInfo.state());
        }

        String sourceBranch = mrInfo.sourceBranch();
        String targetBranch = mrInfo.targetBranch();

        Map<String, List<String>> fileIndex = gitLabService.buildMergedFileIndex(
                request.gitlabUrl(), request.token(),
                request.projectId(), targetBranch, mrInfo.diffs()
        );

        Map<String, Path> dependencySources = dependencyContextService.collectDependencySources(
                request.gitlabUrl(), request.token(),
                request.projectId(), sourceBranch, fileIndex
        );
        log.info("Dependency context: {} known external class names", dependencySources.size());

        // Лямбда-резолвер wildcard-импортов: (simpleName, wildcardPackages) → Optional<qualifiedName>.
        // Строится один раз для всего buildContext(), передаётся в parse().
        BiFunction<String, List<String>, Optional<String>> wildcardResolver =
                buildWildcardResolver(fileIndex, dependencySources);

        List<ClassContext> allContexts = new ArrayList<>();
        Set<String> processedQNames = new LinkedHashSet<>();

        // ── Уровень 0: изменённые файлы ──────────────────────────────────────────
        List<ClassStructure> level0 = new ArrayList<>();
        for (String filePath : mrInfo.changedFiles()) {
            log.debug("Level 0: reading {}", filePath);

            Optional<String> sourceContent = gitLabService.readFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), sourceBranch, filePath
            );
            Optional<String> targetContent = gitLabService.readFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), targetBranch, filePath
            );

            if (sourceContent.isPresent() && targetContent.isEmpty())
                log.debug("{} exists only in {} (just created)", filePath, sourceBranch);
            if (sourceContent.isEmpty() && targetContent.isPresent())
                log.debug("{} exists only in {} (just deleted)", filePath, targetContent);

            sourceContent.ifPresent(src -> {
                List<ClassStructure> parsed =
                        structureParser.parse(src, filePath, 0, wildcardResolver);
                List<StructureNode> srcNodes = nodeMapper.map(src, filePath);
                List<StructureNode> tgtNodes = targetContent
                        .map(tgt -> nodeMapper.map(tgt, filePath))
                        .orElse(null);

                parsed.forEach(cs -> {
                    if (!processedQNames.contains(cs.qualifiedName())) {
                        processedQNames.add(cs.qualifiedName());
                        level0.add(cs);
                        addNestedQNames(cs, processedQNames);
                        allContexts.add(ClassContext.of(
                                cs.qualifiedName(), 0, srcNodes, tgtNodes));
                    }
                });
            });
        }

        // ── Уровни 1..depth: зависимости ───────────────────────────────────────
        List<ClassStructure> currentLevel = level0;
        for (int depth = 1; depth <= request.depth(); depth++) {
            Set<String> referencedTypes = collectAllReferencedTypes(currentLevel);
            referencedTypes.removeAll(processedQNames);
            if (referencedTypes.isEmpty()) break;

            log.debug("Depth {}: resolving {} referenced types", depth, referencedTypes.size());

            List<ClassStructure> nextLevel = new ArrayList<>();
            int finalDepth = depth;

            for (String qName : referencedTypes) {
                // 1. Точный поиск в репозитории
                Optional<Map.Entry<String, String>> repoSource =
                        gitLabService.findJavaFileByQualifiedName(fileIndex, qName)
                                .flatMap(filePath -> gitLabService.readFileContent(
                                        request.gitlabUrl(), request.token(),
                                        request.projectId(), sourceBranch, filePath)
                                        .map(content -> Map.entry(filePath, content)));

                if (repoSource.isPresent()) {
                    addFromRepoSource(repoSource.get(), finalDepth, wildcardResolver,
                            nextLevel, processedQNames, allContexts);
                    continue;
                }

                // 2. Точный поиск в dependencySources
                Path jarPath = dependencySources.get(qName);
                if (jarPath != null) {
                    addFromJar(jarPath, qName, finalDepth, wildcardResolver,
                            nextLevel, processedQNames, allContexts);
                    continue;
                }

                log.debug("Type '{}' not found in repo or dependency sources — skipping", qName);
            }

            currentLevel = nextLevel;
        }

        return new ContextResponse(mrInfo, allContexts, request.depth(), allContexts.size());
    }

    // -------------------------------------------------------------------------
    // Wildcard resolver
    // -------------------------------------------------------------------------

    /**
     * Строит лямбду-резолвер для wildcard-импортов.
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Ищем в {@code fileIndex} файлы {@code SimpleName.java}
     *       в папках, совпадающих с одним из wildcardPackages.</li>
     *   <li>Ищем в ключах {@code dependencySources}
     *       записи, qualified name которых заканчивается на {@code .<simpleName>}
     *       и пакет есть в wildcardPackages.</li>
     *   <li>Если найден ровно один кандидат — возвращаем его qualified name.
     *       Если ни одного кандидата / амбигуация — {@code Optional.empty()},
     *       при амбигуации пишем warn в лог.</li>
     * </ol>
     */
    private BiFunction<String, List<String>, Optional<String>> buildWildcardResolver(
            Map<String, List<String>> fileIndex,
            Map<String, Path> dependencySources) {

        return (simpleName, wildcardPackages) -> {
            List<String> candidates = new ArrayList<>();

            // Ищем в fileIndex: путь вида "<wildcardPkg>/<simpleName>.java"
            String fileName = simpleName + ".java";
            List<String> filePaths = fileIndex.getOrDefault(fileName, List.of());
            for (String path : filePaths) {
                String dirPath = path.contains("/")
                        ? path.substring(0, path.lastIndexOf('/'))
                        : "";
                // dirPath в виде "src/main/java/pkg/sub" — нужно извлечь пакет
                String pkgFromPath = dirPathToPackage(dirPath);
                if (wildcardPackages.contains(pkgFromPath)) {
                    candidates.add(pkgFromPath + "." + simpleName);
                }
            }

            // Ищем в dependencySources: ключ оканчивается на ".SimpleName"
            // и пакет (без последнего сегмента) есть в wildcardPackages
            if (candidates.isEmpty()) {
                String suffix = "." + simpleName;
                for (String qName : dependencySources.keySet()) {
                    if (qName.endsWith(suffix)) {
                        String pkg = qName.substring(0, qName.length() - suffix.length());
                        if (wildcardPackages.contains(pkg)) {
                            candidates.add(qName);
                        }
                    }
                }
            }

            if (candidates.size() == 1) {
                return Optional.of(candidates.get(0));
            }
            if (candidates.size() > 1) {
                log.warn("Ambiguous wildcard resolution for '{}': candidates={} wildcards={}",
                        simpleName, candidates, wildcardPackages);
            }
            return Optional.empty();
        };
    }

    /**
     * Преобразует путь директории в пакет Java.
     * {@code src/main/java/com/example} → {@code com.example}
     * {@code com/example} → {@code com.example}
     */
    private static String dirPathToPackage(String dirPath) {
        // Убираем стандартные префиксы Maven/Gradle source roots
        for (String prefix : List.of("src/main/java/", "src/test/java/", "src/main/kotlin/")) {
            if (dirPath.startsWith(prefix)) {
                dirPath = dirPath.substring(prefix.length());
                break;
            }
        }
        return dirPath.replace('/', '.');
    }

    // -------------------------------------------------------------------------
    // Helpers: adding resolved sources to context
    // -------------------------------------------------------------------------

    private void addFromRepoSource(Map.Entry<String, String> source, int depth,
                                   BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                                   List<ClassStructure> nextLevel, Set<String> processedQNames,
                                   List<ClassContext> allContexts) {
        String filePath = source.getKey();
        String content = source.getValue();
        List<ClassStructure> parsed =
                structureParser.parse(content, filePath, depth, wildcardResolver);
        List<StructureNode> nodes = nodeMapper.map(content, filePath);

        parsed.forEach(cs -> {
            if (!processedQNames.contains(cs.qualifiedName())) {
                processedQNames.add(cs.qualifiedName());
                nextLevel.add(cs);
                addNestedQNames(cs, processedQNames);
                allContexts.add(ClassContext.of(cs.qualifiedName(), depth, nodes, nodes));
            }
        });
    }

    private void addFromJar(Path jarPath, String qName, int depth,
                            BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                            List<ClassStructure> nextLevel, Set<String> processedQNames,
                            List<ClassContext> allContexts) {
        classNameExtractor.extractSourceFile(jarPath, qName).ifPresent(content -> {
            String syntheticPath = qName.replace('.', '/') + ".java";
            List<ClassStructure> parsed =
                    structureParser.parse(content, syntheticPath, depth, wildcardResolver);
            List<StructureNode> nodes = nodeMapper.map(content, syntheticPath);

            parsed.forEach(cs -> {
                if (!processedQNames.contains(cs.qualifiedName())) {
                    processedQNames.add(cs.qualifiedName());
                    nextLevel.add(cs);
                    addNestedQNames(cs, processedQNames);
                    allContexts.add(ClassContext.of(cs.qualifiedName(), depth, nodes, nodes));
                }
            });
            log.debug("Resolved '{}' from sources.jar on disk", qName);
        });
    }

    // -------------------------------------------------------------------------
    // Helpers: misc
    // -------------------------------------------------------------------------

    private void addNestedQNames(ClassStructure cs, Set<String> processed) {
        cs.nestedClasses().forEach(nc -> {
            processed.add(nc.qualifiedName());
            addNestedQNames(nc, processed);
        });
    }

    private Set<String> collectAllReferencedTypes(List<ClassStructure> classes) {
        Set<String> types = new LinkedHashSet<>();
        classes.forEach(cs -> types.addAll(structureParser.collectReferencedTypes(cs)));
        return types;
    }
}
