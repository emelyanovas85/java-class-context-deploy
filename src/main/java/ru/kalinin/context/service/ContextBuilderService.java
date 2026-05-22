package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.kalinin.context.dependency.DependencyClassNameExtractor;
import ru.kalinin.context.exception.MergeRequestAlreadyMergedException;
import ru.kalinin.context.model.*;
import ru.kalinin.context.parser.JavaStructureParser;
import ru.kalinin.context.parser.StructureNodeMapper;
import ru.kalinin.context.parser.UnresolvedTypeRef;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

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
 *   <li>Уровни 1..depth: зависимости; поиск по repo выполняется
 *       через {@link #findRepoSourceForType}: сначала точный поиск, затем
 *       последовательное откусывание сегментов (для Outer.Inner, Outer.Inner.Deep).</li>
 *   <li>Финальный пост-проход: строится {@code enrichedQNames} = processedQNames +
 *       все qualified refs из allParsed. Через него резолвятся simple names
 *       (например {@code Description} → {@code io.qameta.allure.Description}).
 *       Типы, не попавшие в processedQNames, ищутся в repo / dependencySources.</li>
 * </ol>
 *
 * <h3>Граф зависимостей</h3>
 * <p>Каждый {@link ClassContext} несёт сквозной {@code id} (монотонно возрастающий)
 * и {@code callerIds} — множество id классов, которые непосредственно ссылаются
 * на данный. Классы уровня 0 имеют пустой {@code callerIds}.
 *
 * <h3>Пост-резолвинг wildcard-типов</h3>
 * <p>Если при парсинге тип не удалось резолвить через wildcardResolver (например,
 * зависимость не была в dependencySources), имя сохраняется как simple name.
 * Финальный проход использует {@code enrichedQNames} только для {@link #resolveRef} —
 * основной {@code processedQNames} не изменяется, поэтому классы из jar/repo,
 * ещё не загруженные, не блокируются как «уже обработанные».
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

        BiFunction<String, List<String>, Optional<String>> wildcardResolver =
                buildWildcardResolver(fileIndex, dependencySources);

        List<ClassContext> allContexts = new ArrayList<>();
        Set<String> processedQNames = new LinkedHashSet<>();

        // qName → id уже добавленного ClassContext — для построения callerIds
        Map<String, Integer> qNameToId = new LinkedHashMap<>();
        AtomicInteger idCounter = new AtomicInteger(1);

        // ── Уровень 0: изменённые файлы ────────────────────────────────────────────
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
                        int id = idCounter.getAndIncrement();
                        processedQNames.add(cs.qualifiedName());
                        qNameToId.put(cs.qualifiedName(), id);
                        level0.add(cs);
                        addNestedQNames(cs, processedQNames);
                        allContexts.add(ClassContext.of(
                                id, Set.of(), cs.qualifiedName(), 0, srcNodes, tgtNodes));
                    }
                });
            });
        }

        // ── Уровни 1..depth: зависимости ───────────────────────────────────────────
        List<ClassStructure> currentLevel = level0;
        List<ClassStructure> allParsed = new ArrayList<>(level0);

        for (int depth = 1; depth <= request.depth(); depth++) {
            // Собираем: для каждого referenced type → set id классов, которые на него ссылаются
            Map<String, Set<Integer>> refToCallerIds = collectRefToCallerIds(currentLevel, processedQNames, qNameToId);
            Set<String> referencedTypes = new LinkedHashSet<>(refToCallerIds.keySet());
            referencedTypes.removeAll(processedQNames);
            if (referencedTypes.isEmpty()) break;

            log.debug("Depth {}: resolving {} referenced types", depth, referencedTypes.size());

            List<ClassStructure> nextLevel = new ArrayList<>();
            int finalDepth = depth;

            for (String qName : referencedTypes) {
                Set<Integer> callerIds = refToCallerIds.getOrDefault(qName, Set.of());

                // 1. Поиск в репозитории: сначала точный, затем откусывание сегментов
                Optional<Map.Entry<String, String>> repoSource =
                        findRepoSourceForType(qName, fileIndex,
                                request.gitlabUrl(), request.token(),
                                request.projectId(), sourceBranch, processedQNames);

                if (repoSource.isPresent()) {
                    addFromRepoSource(repoSource.get(), finalDepth, callerIds, wildcardResolver,
                            nextLevel, processedQNames, qNameToId, idCounter, allContexts);
                    continue;
                }

                // 2. Точный поиск в dependencySources
                Path jarPath = dependencySources.get(qName);
                if (jarPath != null) {
                    addFromJar(jarPath, qName, finalDepth, callerIds, wildcardResolver,
                            nextLevel, processedQNames, qNameToId, idCounter, allContexts);
                    continue;
                }

                log.debug("Type '{}' not found in repo or dependency sources — skipping", qName);
            }

            allParsed.addAll(nextLevel);
            currentLevel = nextLevel;
        }

        // ── Финальный пост-проход ───────────────────────────────────────────────────
        Set<String> enrichedQNames = new LinkedHashSet<>(processedQNames);
        collectRawRefs(allParsed).stream()
                .filter(ref -> !ref.isUnresolved())
                .map(UnresolvedTypeRef::name)
                .forEach(enrichedQNames::add);

        Map<String, Set<Integer>> finalRefToCallerIds = collectRefToCallerIds(allParsed, enrichedQNames, qNameToId);
        Set<String> unresolvedBeforeFinalPass = finalRefToCallerIds.keySet().stream()
                .filter(name -> !processedQNames.contains(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!unresolvedBeforeFinalPass.isEmpty()) {
            log.info("Final pass: resolving {} previously unresolved types", unresolvedBeforeFinalPass.size());
            List<ClassStructure> finalPassLevel = new ArrayList<>();

            for (String qName : unresolvedBeforeFinalPass) {
                Set<Integer> callerIds = finalRefToCallerIds.getOrDefault(qName, Set.of());

                Optional<Map.Entry<String, String>> repoSource =
                        findRepoSourceForType(qName, fileIndex,
                                request.gitlabUrl(), request.token(),
                                request.projectId(), sourceBranch, processedQNames);

                if (repoSource.isPresent()) {
                    addFromRepoSource(repoSource.get(), request.depth(), callerIds, wildcardResolver,
                            finalPassLevel, processedQNames, qNameToId, idCounter, allContexts);
                    continue;
                }

                Path jarPath = dependencySources.get(qName);
                if (jarPath != null) {
                    addFromJar(jarPath, qName, request.depth(), callerIds, wildcardResolver,
                            finalPassLevel, processedQNames, qNameToId, idCounter, allContexts);
                    continue;
                }
            }

            allParsed.addAll(finalPassLevel);
        }

        // ── Итоговый отчёт: нерезолвленные типы ───────────────────────────────────
        if (log.isInfoEnabled()) {
            collectRawRefs(allParsed).stream()
                    .filter(ref -> !ref.isUnresolved())
                    .map(UnresolvedTypeRef::name)
                    .forEach(enrichedQNames::add);
            enrichedQNames.addAll(processedQNames);

            Set<String> unresolved = collectRawRefs(allParsed).stream()
                    .map(ref -> resolveRef(ref, enrichedQNames))
                    .filter(name -> !enrichedQNames.contains(name) && !name.contains("."))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (unresolved.isEmpty()) {
                log.info("All referenced types resolved successfully");
            } else {
                log.info("Unresolved types ({}):\n  {}",
                        unresolved.size(),
                        String.join("\n  ", unresolved));
            }
        }

        return new ContextResponse(mrInfo, allContexts, request.depth(), allContexts.size());
    }

    // -------------------------------------------------------------------------
    // Repo source lookup
    // -------------------------------------------------------------------------

    private Optional<Map.Entry<String, String>> findRepoSourceForType(
            String qName,
            Map<String, List<String>> fileIndex,
            String gitlabUrl, String token, String projectId, String branch,
            Set<String> processedQNames) {

        Optional<Map.Entry<String, String>> exact =
                readRepoFile(qName, fileIndex, gitlabUrl, token, projectId, branch);
        if (exact.isPresent()) return exact;

        String candidate = qName;
        while (candidate.contains(".")) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            Optional<Map.Entry<String, String>> found =
                    readRepoFile(candidate, fileIndex, gitlabUrl, token, projectId, branch);
            if (found.isPresent()) {
                processedQNames.add(qName);
                log.debug("Type '{}' resolved via outer class '{}'", qName, candidate);
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<Map.Entry<String, String>> readRepoFile(
            String qName,
            Map<String, List<String>> fileIndex,
            String gitlabUrl, String token, String projectId, String branch) {
        return gitLabService.findJavaFileByQualifiedName(fileIndex, qName)
                .flatMap(filePath -> gitLabService.readFileContent(
                        gitlabUrl, token, projectId, branch, filePath)
                        .map(content -> Map.entry(filePath, content)));
    }

    // -------------------------------------------------------------------------
    // Wildcard resolver
    // -------------------------------------------------------------------------

    private BiFunction<String, List<String>, Optional<String>> buildWildcardResolver(
            Map<String, List<String>> fileIndex,
            Map<String, Path> dependencySources) {

        return (simpleName, wildcardPackages) -> {
            List<String> candidates = new ArrayList<>();

            String fileName = simpleName + ".java";
            List<String> filePaths = fileIndex.getOrDefault(fileName, List.of());
            for (String path : filePaths) {
                String dirPath = path.contains("/")
                        ? path.substring(0, path.lastIndexOf('/'))
                        : "";
                String pkgFromPath = dirPathToPackage(dirPath);
                if (wildcardPackages.contains(pkgFromPath)) {
                    candidates.add(pkgFromPath + "." + simpleName);
                }
            }

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

            if (candidates.size() == 1) return Optional.of(candidates.get(0));
            if (candidates.size() > 1) {
                log.warn("Ambiguous wildcard resolution for '{}': candidates={} wildcards={}",
                        simpleName, candidates, wildcardPackages);
            }
            return Optional.empty();
        };
    }

    private static String dirPathToPackage(String dirPath) {
        for (String prefix : List.of("src/main/java/", "src/test/java/", "src/main/kotlin/")) {
            if (dirPath.startsWith(prefix)) {
                dirPath = dirPath.substring(prefix.length());
                break;
            }
        }
        return dirPath.replace('/', '.');
    }

    // -------------------------------------------------------------------------
    // collectRawRefs / collectAndResolveRefs / collectRefToCallerIds
    // -------------------------------------------------------------------------

    /**
     * Собирает сырые {@link UnresolvedTypeRef} без резолвинга для заданных классов.
     */
    private Set<UnresolvedTypeRef> collectRawRefs(List<ClassStructure> classes) {
        Set<UnresolvedTypeRef> refs = new LinkedHashSet<>();
        for (ClassStructure cs : classes) {
            refs.addAll(structureParser.collectReferencedTypes(cs));
        }
        return refs;
    }

    /**
     * Собирает все типы-зависимости со всех классов и пробует
     * резолвить нерезолвленные (simple name) через уже известные {@code knownQNames}.
     */
    private Set<String> collectAndResolveRefs(List<ClassStructure> classes,
                                               Set<String> knownQNames) {
        Set<String> resolved = new LinkedHashSet<>();
        for (UnresolvedTypeRef ref : collectRawRefs(classes)) {
            resolved.add(resolveRef(ref, knownQNames));
        }
        return resolved;
    }

    /**
     * Для каждого referenced type строит mapping: resolvedQName → Set&lt;callerId&gt;.
     * callerId — id ClassContext того класса, который ссылается на данный тип.
     * Классы, для которых id ещё не назначен (нет в qNameToId), пропускаются как caller.
     */
    private Map<String, Set<Integer>> collectRefToCallerIds(
            List<ClassStructure> classes,
            Set<String> knownQNames,
            Map<String, Integer> qNameToId) {

        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        for (ClassStructure cs : classes) {
            Integer callerId = qNameToId.get(cs.qualifiedName());
            for (UnresolvedTypeRef ref : structureParser.collectReferencedTypes(cs)) {
                String resolved = resolveRef(ref, knownQNames);
                result.computeIfAbsent(resolved, k -> new LinkedHashSet<>());
                if (callerId != null) {
                    result.get(resolved).add(callerId);
                }
            }
        }
        return result;
    }

    /**
     * Пробует резолвить {@link UnresolvedTypeRef} через {@code knownQNames}.
     * Если имя уже qualified (содержит точку) — возвращает как есть.
     * Если simple name — ищет в knownQNames по правилу:
     * {@code qName.endsWith("." + simpleName) && wildcardPackages.contains(pkg(qName))}.
     */
    private String resolveRef(UnresolvedTypeRef ref, Set<String> knownQNames) {
        if (!ref.isUnresolved()) return ref.name();

        String simpleName = ref.name();
        List<String> wildcards = ref.wildcardPackages();
        if (wildcards.isEmpty()) return simpleName;

        String suffix = "." + simpleName;
        List<String> candidates = new ArrayList<>();
        for (String qName : knownQNames) {
            if (qName.endsWith(suffix)) {
                String pkg = qName.substring(0, qName.length() - suffix.length());
                if (wildcards.contains(pkg)) {
                    candidates.add(qName);
                }
            }
        }

        if (candidates.size() == 1) {
            log.debug("Post-resolved '{}' → '{}' via wildcardImports={}",
                    simpleName, candidates.get(0), wildcards);
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            log.warn("Ambiguous post-resolution for '{}': candidates={}", simpleName, candidates);
        }
        return simpleName;
    }

    // -------------------------------------------------------------------------
    // Helpers: adding resolved sources to context
    // -------------------------------------------------------------------------

    private void addFromRepoSource(Map.Entry<String, String> source, int depth,
                                   Set<Integer> callerIds,
                                   BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                                   List<ClassStructure> nextLevel, Set<String> processedQNames,
                                   Map<String, Integer> qNameToId, AtomicInteger idCounter,
                                   List<ClassContext> allContexts) {
        String filePath = source.getKey();
        String content = source.getValue();
        List<ClassStructure> parsed =
                structureParser.parse(content, filePath, depth, wildcardResolver);
        List<StructureNode> nodes = nodeMapper.map(content, filePath);

        parsed.forEach(cs -> {
            if (!processedQNames.contains(cs.qualifiedName())) {
                int id = idCounter.getAndIncrement();
                processedQNames.add(cs.qualifiedName());
                qNameToId.put(cs.qualifiedName(), id);
                nextLevel.add(cs);
                addNestedQNames(cs, processedQNames);
                allContexts.add(ClassContext.of(id, callerIds, cs.qualifiedName(), depth, nodes, nodes));
            }
        });
    }

    private void addFromJar(Path jarPath, String qName, int depth,
                            Set<Integer> callerIds,
                            BiFunction<String, List<String>, Optional<String>> wildcardResolver,
                            List<ClassStructure> nextLevel, Set<String> processedQNames,
                            Map<String, Integer> qNameToId, AtomicInteger idCounter,
                            List<ClassContext> allContexts) {
        classNameExtractor.extractSourceFile(jarPath, qName).ifPresent(content -> {
            String syntheticPath = qName.replace('.', '/') + ".java";
            List<ClassStructure> parsed =
                    structureParser.parse(content, syntheticPath, depth, wildcardResolver);
            List<StructureNode> nodes = nodeMapper.map(content, syntheticPath);

            parsed.forEach(cs -> {
                if (!processedQNames.contains(cs.qualifiedName())) {
                    int id = idCounter.getAndIncrement();
                    processedQNames.add(cs.qualifiedName());
                    qNameToId.put(cs.qualifiedName(), id);
                    nextLevel.add(cs);
                    addNestedQNames(cs, processedQNames);
                    allContexts.add(ClassContext.of(id, callerIds, cs.qualifiedName(), depth, nodes, nodes));
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
}
