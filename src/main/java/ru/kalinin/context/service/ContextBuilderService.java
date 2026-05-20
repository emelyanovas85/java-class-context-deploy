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

/**
 * Основной сервис: строит многоуровневый контекст для мёрж-реквеста.
 *
 * <h3>Алгоритм</h3>
 * <ol>
 *   <li>Проверка состояния MR: только opened/locked проходят дальше.</li>
 *   <li>Построение мёрженного файлового индекса: target + patch из diff MR.</li>
 *   <li>Сбор карты зависимостей: qualified name → путь к jar на диске.</li>
 *   <li>Уровень 0: читаем изменённые .java-файлы из source-ветки,
 *       для каждого строим пару structureSource / structureTarget.</li>
 *   <li>Уровни N ≥ 1: зависимости, не входящие в diff MR. Порядок поиска для каждого qName:
 *       <ol>
 *         <li>Точный поиск в репозитории по qualified name (fileIndex).</li>
 *         <li>Точный поиск в sources.jar (dependencySources) по qualified name.</li>
 *         <li>Поиск по simple name (последний сегмент) в fileIndex —
 *             решает wildcard-импорты из проекта.</li>
 *         <li>Поиск по simple name в dependencySources —
 *             решает wildcard-импорты из зависимостей.</li>
 *       </ol>
 *       Если на любом шаге найдено более одного кандидата — пропускаем с предупреждением.</li>
 *   </ol>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextBuilderService {

    /** Состояния MR, при которых анализ возможен. */
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
                List<ClassStructure> parsed = structureParser.parse(src, filePath, 0);
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

                // Шаг 1: точный поиск в репозитории по qualified name
                Optional<Map.Entry<String, String>> repoSource =
                        gitLabService.findJavaFileByQualifiedName(fileIndex, qName)
                                .flatMap(filePath -> gitLabService.readFileContent(
                                        request.gitlabUrl(), request.token(),
                                        request.projectId(), sourceBranch, filePath)
                                        .map(content -> Map.entry(filePath, content)));

                if (repoSource.isPresent()) {
                    addFromRepoSource(repoSource.get(), finalDepth, nextLevel,
                            processedQNames, allContexts);
                    continue;
                }

                // Шаг 2: точный поиск в dependencySources по qualified name
                Path jarPath = dependencySources.get(qName);
                if (jarPath != null) {
                    addFromJar(jarPath, qName, finalDepth, nextLevel, processedQNames, allContexts);
                    continue;
                }

                // Шаги 3–4: поиск по simple name — решает wildcard-импорты
                String simpleName = simpleName(qName);

                // Шаг 3: wildcard в репозитории — ищем файл, чей имя совпадает с simpleName
                List<String> repoWildcardPaths = gitLabService.findJavaFilesBySimpleName(fileIndex, simpleName);
                if (!repoWildcardPaths.isEmpty()) {
                    if (repoWildcardPaths.size() > 1) {
                        log.warn("Ambiguous wildcard resolution for '{}' in repo: candidates={} — skipping",
                                qName, repoWildcardPaths);
                        continue;
                    }
                    String resolvedPath = repoWildcardPaths.get(0);
                    Optional<Map.Entry<String, String>> wildcardRepoSource =
                            gitLabService.readFileContent(
                                    request.gitlabUrl(), request.token(),
                                    request.projectId(), sourceBranch, resolvedPath)
                                    .map(content -> Map.entry(resolvedPath, content));
                    if (wildcardRepoSource.isPresent()) {
                        log.debug("Resolved wildcard '{}' → repo file '{}'", qName, resolvedPath);
                        addFromRepoSource(wildcardRepoSource.get(), finalDepth, nextLevel,
                                processedQNames, allContexts);
                        continue;
                    }
                }

                // Шаг 4: wildcard в dependencySources — ищем ключи с тем же simpleName
                List<String> depWildcardKeys = dependencySources.keySet().stream()
                        .filter(k -> simpleName(k).equals(simpleName))
                        .toList();
                if (!depWildcardKeys.isEmpty()) {
                    if (depWildcardKeys.size() > 1) {
                        log.warn("Ambiguous wildcard resolution for '{}' in dependency sources: candidates={} — skipping",
                                qName, depWildcardKeys);
                        continue;
                    }
                    String resolvedQName = depWildcardKeys.get(0);
                    Path resolvedJar = dependencySources.get(resolvedQName);
                    log.debug("Resolved wildcard '{}' → dependency class '{}'", qName, resolvedQName);
                    addFromJar(resolvedJar, resolvedQName, finalDepth, nextLevel, processedQNames, allContexts);
                    continue;
                }

                log.debug("Type '{}' not found in repo or dependency sources — skipping", qName);
            }

            currentLevel = nextLevel;
        }

        return new ContextResponse(mrInfo, allContexts, request.depth(), allContexts.size());
    }

    // -------------------------------------------------------------------------
    // Helpers: adding resolved sources to context
    // -------------------------------------------------------------------------

    private void addFromRepoSource(Map.Entry<String, String> source, int depth,
                                   List<ClassStructure> nextLevel, Set<String> processedQNames,
                                   List<ClassContext> allContexts) {
        String filePath = source.getKey();
        String content = source.getValue();
        List<ClassStructure> parsed = structureParser.parse(content, filePath, depth);
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
                            List<ClassStructure> nextLevel, Set<String> processedQNames,
                            List<ClassContext> allContexts) {
        classNameExtractor.extractSourceFile(jarPath, qName).ifPresent(content -> {
            String syntheticPath = qName.replace('.', '/') + ".java";
            List<ClassStructure> parsed = structureParser.parse(content, syntheticPath, depth);
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

    /**
     * Возвращает последний сегмент qualified name («simple name»).
     * Например: {@code Документы.Запрос} → {@code Запрос}.
     */
    private static String simpleName(String qName) {
        int dot = qName.lastIndexOf('.');
        return dot >= 0 ? qName.substring(dot + 1) : qName;
    }

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
