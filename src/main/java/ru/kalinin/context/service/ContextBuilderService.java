package ru.kalinin.context.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.kalinin.context.cache.ClassContextParseCache;
import ru.kalinin.context.cache.ParseCacheEntry;
import ru.kalinin.context.dependency.DependencyClassNameExtractor;
import ru.kalinin.context.dependency.DependencyCoordinate;
import ru.kalinin.context.exception.MergeRequestAlreadyMergedException;
import ru.kalinin.context.model.*;
import ru.kalinin.context.parser.JavaSourceParseService;
import ru.kalinin.context.parser.JavaStructureParser;
import ru.kalinin.context.parser.ParsedJavaFile;
import ru.kalinin.context.parser.StructureNodeMapper;
import ru.kalinin.context.parser.UnresolvedTypeRef;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
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
 *   <li>Сборка wildcardResolver.</li>
 *   <li>Уровень 0: fetch + один parse на ветку (source/target); merge однопоточно.</li>
 *   <li>Уровни 1..depth: волновой BFS — типы волны параллельно; кэш до I/O;
 *       один parse на файл ({@link ru.kalinin.context.parser.JavaSourceParseService});
 *       merge и формирование следующей волны — однопоточно.</li>
 *   <li>Финальный пасс: аналогично, параллельно
 *       по нерезолвленным типам.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>Общие мутабельные коллекции ({@code processedQNames}, {@code qNameToId},
 * {@code allContexts}) никогда не трогаются из параллельных задач. Все задачи
 * возвращают неизменяемый {@link DepthResult}, merge выполняется
 * в главном потоке после сбора всех результатов волны.
 *
 * <h3>Граф зависимостей</h3>
 * <p>Каждый {@link ClassContext} несёт сквозной {@code id} (монотонно возрастающий)
 * и {@code callerIds} — множество id классов, которые непосредственно ссылаются
 * на данный. Классы уровня 0 имеют пустой {@code callerIds}.
 */
@Slf4j
@Service
public class ContextBuilderService {

    private static final Set<String> ANALYZABLE_STATES = Set.of("opened", "locked");

    private final GitLabService gitLabService;
    private final JavaStructureParser structureParser;
    private final StructureNodeMapper nodeMapper;
    private final JavaSourceParseService sourceParseService;
    private final DependencyContextService dependencyContextService;
    private final DependencyClassNameExtractor classNameExtractor;
    private final ClassContextParseCache parseCache;
    private final ExecutorService ioExecutor;

    public ContextBuilderService(GitLabService gitLabService,
                                 JavaStructureParser structureParser,
                                 StructureNodeMapper nodeMapper,
                                 JavaSourceParseService sourceParseService,
                                 DependencyContextService dependencyContextService,
                                 DependencyClassNameExtractor classNameExtractor,
                                 ClassContextParseCache parseCache,
                                 @Qualifier("ioExecutor") ExecutorService ioExecutor) {
        this.gitLabService = gitLabService;
        this.structureParser = structureParser;
        this.nodeMapper = nodeMapper;
        this.sourceParseService = sourceParseService;
        this.dependencyContextService = dependencyContextService;
        this.classNameExtractor = classNameExtractor;
        this.parseCache = parseCache;
        this.ioExecutor = ioExecutor;
    }

    // ── records ────────────────────────────────────────────────────────────────────

    private record Level0Result(
            String filePath,
            Optional<String> sourceContent,
            Optional<String> targetContent,
            List<ClassStructure> parsed,
            List<StructureNode> srcNodes,
            List<StructureNode> tgtNodes
    ) {}

    record DepthResult(
            String qName,
            Set<Integer> callerIds,
            List<ClassStructure> parsed,
            List<StructureNode> nodes,
            String source,
            int depth
    ) {}

    // ── buildContext ─────────────────────────────────────────────────────────────────

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
                request.projectId(), targetBranch, mrInfo.diffs());

        Map<String, Path> dependencySources = dependencyContextService.collectDependencySources(
                request.gitlabUrl(), request.token(),
                request.projectId(), sourceBranch, fileIndex);
        log.info("Dependency context: {} known external class names", dependencySources.size());

        BiFunction<String, List<String>, Optional<String>> wildcardResolver =
                buildWildcardResolver(fileIndex, dependencySources);

        List<ClassContext> allContexts = new ArrayList<>();
        Set<String> processedQNames = new LinkedHashSet<>();
        Map<String, Integer> qNameToId = new LinkedHashMap<>();
        Map<String, String> nestedToRootTopLevel = new LinkedHashMap<>();
        AtomicInteger idCounter = new AtomicInteger(1);

        // Счётчики для summary-лога
        AtomicInteger totalRequested = new AtomicInteger(0);
        AtomicInteger totalResolved  = new AtomicInteger(0);
        AtomicInteger totalSkipped   = new AtomicInteger(0);

        // ── Уровень 0 ─────────────────────────────────────────────────────────────────
        List<CompletableFuture<Level0Result>> level0Futures = mrInfo.changedFiles().stream()
                .map(filePath -> CompletableFuture.supplyAsync(() -> {
                    log.debug("Level 0 [parallel]: fetch+parse {}", filePath);
                    Optional<String> src = gitLabService.readFileContent(
                            request.gitlabUrl(), request.token(),
                            request.projectId(), sourceBranch, filePath);
                    Optional<String> tgt = gitLabService.readFileContent(
                            request.gitlabUrl(), request.token(),
                            request.projectId(), targetBranch, filePath);
                    ParsedJavaFile srcFile = src
                            .map(s -> sourceParseService.parse(s, filePath, 0, wildcardResolver))
                            .orElse(ParsedJavaFile.empty());
                    List<StructureNode> tgtNodes = tgt
                            .map(s -> sourceParseService.parse(s, filePath, 0, wildcardResolver).nodes())
                            .orElse(null);
                    return new Level0Result(
                            filePath, src, tgt, srcFile.structures(), srcFile.nodes(), tgtNodes);
                }, ioExecutor))
                .toList();

        List<ClassStructure> level0 = new ArrayList<>();
        for (Level0Result r : awaitAll(level0Futures)) {
            if (r.sourceContent().isPresent() && r.targetContent().isEmpty())
                log.debug("{} exists only in source branch (just created)", r.filePath());
            if (r.sourceContent().isEmpty() && r.targetContent().isPresent())
                log.debug("{} exists only in target branch (just deleted)", r.filePath());
            if (r.sourceContent().isEmpty()) continue;

            String src0 = repoSource(r.filePath());
            for (ClassStructure cs : r.parsed()) {
                mapNestedToRootTopLevel(cs, nestedToRootTopLevel);
                if (processedQNames.add(cs.qualifiedName())) {
                    int id = idCounter.getAndIncrement();
                    qNameToId.put(cs.qualifiedName(), id);
                    level0.add(cs);
                    List<StructureNode> srcForClass =
                            structureNodesForClass(r.srcNodes(), r.parsed(), cs);
                    List<StructureNode> tgtForClass = r.tgtNodes() != null
                            ? structureNodesForClass(r.tgtNodes(), r.parsed(), cs)
                            : null;
                    ClassContext ctx = ClassContext.of(
                            id, Set.of(), cs.qualifiedName(), 0, src0, srcForClass, tgtForClass);
                    allContexts.add(ctx);
                    storeParseCache(src0, cs.qualifiedName(), r.parsed(), r.srcNodes(), ctx);
                }
            }
        }

        // ── Уровни 1..depth ─────────────────────────────────────────────────────────────
        List<ClassStructure> currentLevel = level0;
        List<ClassStructure> allParsed = new ArrayList<>(level0);

        for (int depth = 1; depth <= request.depth(); depth++) {
            Map<String, Set<Integer>> refToCallerIds =
                    collectRefToCallerIds(currentLevel, processedQNames, qNameToId);
            Map<Integer, String> idToQn = invertQNameToId(qNameToId);
            WavePartition partition = partitionWave(
                    refToCallerIds, processedQNames, nestedToRootTopLevel, idToQn);
            List<String> wave = partition.toFetch();
            if (wave.isEmpty() && partition.internalNestedSkipped().isEmpty()) break;

            processedQNames.addAll(partition.internalNestedSkipped());
            if (wave.isEmpty()) break;

            log.debug("Depth {}: resolving {} types in parallel ({} inline nested skipped)",
                    depth, wave.size(), partition.internalNestedSkipped().size());
            processedQNames.addAll(wave);

            final int currentDepth = depth;
            List<CompletableFuture<DepthResult>> futures = wave.stream()
                    .map(qName -> CompletableFuture.supplyAsync(
                            () -> fetchAndParse(qName,
                                    refToCallerIds.getOrDefault(qName, Set.of()),
                                    fileIndex, dependencySources, wildcardResolver,
                                    request, sourceBranch, currentDepth,
                                    totalRequested, totalResolved, totalSkipped),
                            ioExecutor))
                    .toList();

            List<ClassStructure> nextLevel = new ArrayList<>();
            for (DepthResult r : mergeResults(awaitAll(futures))) {
                registerDepthResult(r, qNameToId, idCounter, allContexts, nextLevel, nestedToRootTopLevel);
            }

            allParsed.addAll(nextLevel);
            currentLevel = nextLevel;
        }

        // ── Финальный пасс ─────────────────────────────────────────────────────────────
        Set<String> enrichedQNames = new LinkedHashSet<>(processedQNames);
        collectRawRefs(allParsed).stream()
                .filter(ref -> !ref.isUnresolved())
                .map(UnresolvedTypeRef::name)
                .forEach(enrichedQNames::add);

        Map<String, Set<Integer>> finalRefToCallerIds =
                collectRefToCallerIds(allParsed, enrichedQNames, qNameToId);
        Map<Integer, String> finalIdToQn = invertQNameToId(qNameToId);
        WavePartition finalPartition = partitionWave(
                finalRefToCallerIds, processedQNames, nestedToRootTopLevel, finalIdToQn);
        List<String> finalWave = finalPartition.toFetch();

        if (!finalWave.isEmpty() || !finalPartition.internalNestedSkipped().isEmpty()) {
            processedQNames.addAll(finalPartition.internalNestedSkipped());
        }

        if (!finalWave.isEmpty()) {
            log.info("Final pass: resolving {} previously unresolved types in parallel ({} inline nested skipped)",
                    finalWave.size(), finalPartition.internalNestedSkipped().size());
            processedQNames.addAll(finalWave);

            List<CompletableFuture<DepthResult>> finalFutures = finalWave.stream()
                    .map(qName -> CompletableFuture.supplyAsync(
                            () -> fetchAndParse(qName,
                                    finalRefToCallerIds.getOrDefault(qName, Set.of()),
                                    fileIndex, dependencySources, wildcardResolver,
                                    request, sourceBranch, request.depth(),
                                    totalRequested, totalResolved, totalSkipped),
                            ioExecutor))
                    .toList();

            List<ClassStructure> finalLevel = new ArrayList<>();
            for (DepthResult r : mergeResults(awaitAll(finalFutures))) {
                registerDepthResult(r, qNameToId, idCounter, allContexts, finalLevel, nestedToRootTopLevel);
            }
            allParsed.addAll(finalLevel);
        }

        // ── Summary + нерезолвленные ────────────────────────────────────────────────
        if (log.isInfoEnabled()) {
            int req  = totalRequested.get();
            int res  = totalResolved.get();
            int skip = totalSkipped.get();
            log.info("Type resolution summary: {} requested, {} resolved, {} skipped",
                    req, res, skip);

            Set<String> allKnown = new LinkedHashSet<>(processedQNames);
            collectRawRefs(allParsed).stream()
                    .filter(ref -> !ref.isUnresolved())
                    .map(UnresolvedTypeRef::name)
                    .forEach(allKnown::add);
            Set<String> unresolved = collectRawRefs(allParsed).stream()
                    .map(ref -> resolveRef(ref, allKnown))
                    .filter(name -> !allKnown.contains(name) && !name.contains("."))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (unresolved.isEmpty()) {
                log.info("All referenced types resolved successfully");
            } else {
                log.info("Unresolved types ({}):\n  {}",
                        unresolved.size(), String.join("\n  ", unresolved));
            }
        }

        List<ClassContext> resultContexts =
                enrichAndFilterContexts(allContexts, allParsed, qNameToId, nestedToRootTopLevel);
        return new ContextResponse(mrInfo, resultContexts, request.depth(), resultContexts.size());
    }

    // ── mergeResults ────────────────────────────────────────────────────────────────

    /**
     * Схлопывает результаты волны с одинаковым резолведшимся классом.
     *
     * <p>Это возникает когда несколько разных запросов ({@code qName}) волны
     * резолвировались в один и тот же outer класс (например,
     * {@code userInterface.ComplexTable.Row} и {@code userInterface.ComplexTable}
     * оба резолвировались в {@code ComplexTable.java}).
     * В таком случае результаты имеют одинаковый набор parsed-классов,
     * но разные {@code callerIds}. Метод объединяет все {@code callerIds}
     * в одном {@link DepthResult}, оставляя остальные поля без изменений.
     *
     * <p>Группировка по ключу {@code (top-level parsed класс + source)} гарантирует,
     * что не будет смешивания callerIds от разных файлов.
     * Разные {@code qName} из одного файла (outer и nested) остаются отдельными
     * результатами — иначе {@link #registerDepthResult} зарегистрирует только один тип.
     */
    List<DepthResult> mergeResults(List<DepthResult> raw) {
        record Key(String topClassName, String source) {}

        Map<Key, Map<String, DepthResult>> merged = new LinkedHashMap<>();
        for (DepthResult r : raw) {
            if (r == null || r.parsed().isEmpty()) continue;
            Key key = new Key(r.parsed().get(0).qualifiedName(), r.source());
            Map<String, DepthResult> byQName = merged.computeIfAbsent(key, k -> new LinkedHashMap<>());
            DepthResult existing = byQName.get(r.qName());
            if (existing == null) {
                byQName.put(r.qName(), r);
            } else {
                Set<Integer> combined = new LinkedHashSet<>(existing.callerIds());
                combined.addAll(r.callerIds());
                byQName.put(r.qName(), new DepthResult(
                        r.qName(), combined,
                        existing.parsed(), existing.nodes(),
                        existing.source(), existing.depth()));
                log.debug("Merged callerIds for '{}' (qName={}): {} + {} = {}",
                        key.topClassName(), r.qName(),
                        existing.callerIds(), r.callerIds(), combined);
            }
        }
        List<DepthResult> out = new ArrayList<>();
        for (Map<String, DepthResult> byQName : merged.values()) {
            out.addAll(byQName.values());
        }
        return out;
    }

    // ── registerDepthResult / nested helpers ────────────────────────────────────

    /**
     * Регистрирует только запрошенный тип {@code r.qName()} из результата парсинга файла.
     * Nested-классы не добавляются отдельно — они остаются inline в структуре outer-класса.
     */
    private void registerDepthResult(
            DepthResult r,
            Map<String, Integer> qNameToId,
            AtomicInteger idCounter,
            List<ClassContext> allContexts,
            List<ClassStructure> levelList,
            Map<String, String> nestedToRootTopLevel) {
        if (r == null || r.parsed().isEmpty()) return;

        for (ClassStructure root : r.parsed()) {
            mapNestedToRootTopLevel(root, nestedToRootTopLevel);
        }

        ClassStructure requested = findClassStructure(r.parsed(), r.qName());
        if (requested == null) {
            log.debug("Requested type '{}' not found in parsed structures", r.qName());
            return;
        }

        String qn = requested.qualifiedName();
        List<StructureNode> srcNodes = structureNodesForClass(r.nodes(), r.parsed(), requested);
        List<StructureNode> tgtNodes = structureNodesForClass(r.nodes(), r.parsed(), requested);

        if (qNameToId.containsKey(qn)) {
            mergeCallerIds(allContexts, qn, r.callerIds(), qNameToId);
        } else {
            int id = idCounter.getAndIncrement();
            qNameToId.put(qn, id);
            ClassContext ctx = ClassContext.of(
                    id, r.callerIds(), qn, r.depth(), r.source(), srcNodes, tgtNodes);
            allContexts.add(ctx);
            storeParseCache(r.source(), qn, r.parsed(), r.nodes(), ctx);
        }

        if (levelList.stream().noneMatch(c -> c.qualifiedName().equals(qn))) {
            levelList.add(requested);
        }
    }

    private List<StructureNode> structureNodesForClass(
            List<StructureNode> fileNodes,
            List<ClassStructure> fileParsed,
            ClassStructure cs) {
        if (fileNodes == null || fileNodes.isEmpty()) return List.of();
        int topLevelIdx = indexOfTopLevelType(fileParsed, cs.qualifiedName());
        if (topLevelIdx >= 0) {
            return nodeMapper.structureForTopLevelIndex(fileNodes, topLevelIdx);
        }
        return nodeMapper.structureForNestedType(fileNodes, cs.name());
    }

    private static int indexOfTopLevelType(List<ClassStructure> fileParsed, String qualifiedName) {
        for (int i = 0; i < fileParsed.size(); i++) {
            if (fileParsed.get(i).qualifiedName().equals(qualifiedName)) {
                return i;
            }
        }
        return -1;
    }

    record WavePartition(List<String> toFetch, List<String> internalNestedSkipped) {}

    /**
     * Делит кандидатов волны на fetch и inline-only nested.
     * Nested, на который ссылаются только классы внутри одного top-level outer, не fetch'ится отдельно.
     */
    static WavePartition partitionWave(
            Map<String, Set<Integer>> refToCallerIds,
            Set<String> processedQNames,
            Map<String, String> nestedToRootTopLevel,
            Map<Integer, String> idToQn) {
        List<String> toFetch = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> entry : refToCallerIds.entrySet()) {
            String target = entry.getKey();
            if (processedQNames.contains(target)) continue;
            if (shouldFetchType(target, entry.getValue(), nestedToRootTopLevel, idToQn)) {
                toFetch.add(target);
            } else {
                skipped.add(target);
                log.debug("Skipping inline nested '{}' — enclosed in '{}'",
                        target, nestedToRootTopLevel.get(target));
            }
        }
        return new WavePartition(toFetch, skipped);
    }

    /**
     * @return {@code true} если тип нужно fetch'ить как отдельный {@link ClassContext}
     */
    static boolean shouldFetchType(
            String targetQn,
            Set<Integer> callerIds,
            Map<String, String> nestedToRootTopLevel,
            Map<Integer, String> idToQn) {
        String rootTop = nestedToRootTopLevel.get(targetQn);
        if (rootTop == null) return true;
        if (callerIds == null || callerIds.isEmpty()) return true;
        for (Integer callerId : callerIds) {
            String callerQn = idToQn.get(callerId);
            if (callerQn == null) return true;
            if (!isEnclosedInTopLevel(callerQn, rootTop, nestedToRootTopLevel)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Integer, String> invertQNameToId(Map<String, Integer> qNameToId) {
        Map<Integer, String> idToQn = new LinkedHashMap<>();
        qNameToId.forEach((qn, id) -> idToQn.put(id, qn));
        return idToQn;
    }

    private static void mapNestedToRootTopLevel(
            ClassStructure rootTopLevel,
            Map<String, String> nestedToRootTopLevel) {
        mapNestedRecursive(rootTopLevel.nestedClasses(), rootTopLevel.qualifiedName(), nestedToRootTopLevel);
    }

    private static void mapNestedRecursive(
            List<ClassStructure> nested,
            String rootTopLevelQn,
            Map<String, String> nestedToRootTopLevel) {
        for (ClassStructure nc : nested) {
            nestedToRootTopLevel.put(nc.qualifiedName(), rootTopLevelQn);
            mapNestedRecursive(nc.nestedClasses(), rootTopLevelQn, nestedToRootTopLevel);
        }
    }

    private static ClassStructure findClassStructure(List<ClassStructure> parsed, String qName) {
        for (ClassStructure cs : parsed) {
            ClassStructure found = findClassStructureRecursive(cs, qName);
            if (found != null) return found;
        }
        return null;
    }

    private static ClassStructure findClassStructureRecursive(ClassStructure cs, String qName) {
        if (cs.qualifiedName().equals(qName)) return cs;
        for (ClassStructure nested : cs.nestedClasses()) {
            ClassStructure found = findClassStructureRecursive(nested, qName);
            if (found != null) return found;
        }
        return null;
    }

    private static ClassStructure findInAllParsed(List<ClassStructure> allParsed, String qName) {
        for (ClassStructure cs : allParsed) {
            ClassStructure found = findClassStructureRecursive(cs, qName);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Nested-типы, для которых outer уже зарегистрирован — отдельный контекст не нужен.
     */
    static Set<String> nestedContextsSuppressedWhenOuterPresent(
            Map<String, String> nestedToRootTopLevel,
            Set<String> registeredQNames) {
        Set<String> suppressed = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : nestedToRootTopLevel.entrySet()) {
            if (registeredQNames.contains(e.getValue())) {
                suppressed.add(e.getKey());
            }
        }
        return suppressed;
    }

    private static boolean isEnclosedInTopLevel(
            String callerQn,
            String rootTopLevelQn,
            Map<String, String> nestedToRootTopLevel) {
        if (callerQn.equals(rootTopLevelQn)) return true;
        return rootTopLevelQn.equals(nestedToRootTopLevel.get(callerQn));
    }

    /**
     * Дополняет {@code callerIds} по графу ссылок (через id, не по строковому имени)
     * и исключает зависимости (level &gt; 0) без потребителей.
     */
    private List<ClassContext> enrichAndFilterContexts(
            List<ClassContext> allContexts,
            List<ClassStructure> allParsed,
            Map<String, Integer> qNameToId,
            Map<String, String> nestedToRootTopLevel) {
        Set<String> knownQNames = new LinkedHashSet<>(qNameToId.keySet());
        collectRawRefs(allParsed).stream()
                .filter(ref -> !ref.isUnresolved())
                .map(UnresolvedTypeRef::name)
                .forEach(knownQNames::add);

        Set<String> suppressSeparateNested = nestedContextsSuppressedWhenOuterPresent(
                nestedToRootTopLevel, qNameToId.keySet());
        Set<String> expandNested = new LinkedHashSet<>(suppressSeparateNested);

        Map<Integer, Set<Integer>> targetIdToCallers = new LinkedHashMap<>();
        for (ClassStructure cs : allParsed) {
            Integer callerId = qNameToId.get(cs.qualifiedName());
            if (callerId == null) continue;
            for (UnresolvedTypeRef ref : structureParser.collectReferencedTypes(cs)) {
                String resolved = resolveRef(ref, knownQNames);
                Integer targetId = qNameToId.get(resolved);
                if (targetId != null) {
                    targetIdToCallers
                            .computeIfAbsent(targetId, k -> new LinkedHashSet<>())
                            .add(callerId);
                }
            }
        }
        redirectCallersFromNestedToOuter(
                suppressSeparateNested, nestedToRootTopLevel, qNameToId,
                targetIdToCallers, null);

        Map<String, Set<Integer>> refToCallerIds =
                collectRefToCallerIds(allParsed, knownQNames, qNameToId);
        redirectCallersFromNestedToOuter(
                suppressSeparateNested, nestedToRootTopLevel, qNameToId,
                null, refToCallerIds);

        List<ClassContext> result = new ArrayList<>(allContexts.size());
        for (ClassContext ctx : allContexts) {
            if (suppressSeparateNested.contains(ctx.name())) {
                log.debug("Skipping duplicate nested context '{}' — covered by outer '{}'",
                        ctx.name(), nestedToRootTopLevel.get(ctx.name()));
                continue;
            }

            ClassContext ctxOut = applyNestedVisibility(ctx, allParsed, nestedToRootTopLevel, expandNested);

            if (ctxOut.level() == 0) {
                result.add(ctxOut);
                continue;
            }
            Set<Integer> callers = new LinkedHashSet<>(ctxOut.callerIds());
            callers.addAll(targetIdToCallers.getOrDefault(ctxOut.id(), Set.of()));
            callers.addAll(refToCallerIds.getOrDefault(ctxOut.name(), Set.of()));
            if (callers.isEmpty()) {
                log.debug("Skipping context '{}' (level={}) — no callers", ctxOut.name(), ctxOut.level());
                continue;
            }
            result.add(callers.equals(ctxOut.callerIds()) ? ctxOut : withCallerIds(ctxOut, callers));
        }
        return result;
    }

    private static void redirectCallersFromNestedToOuter(
            Set<String> suppressSeparateNested,
            Map<String, String> nestedToRootTopLevel,
            Map<String, Integer> qNameToId,
            Map<Integer, Set<Integer>> targetIdToCallers,
            Map<String, Set<Integer>> refToCallerIds) {
        for (String nestedQn : suppressSeparateNested) {
            String outerQn = nestedToRootTopLevel.get(nestedQn);
            if (outerQn == null) continue;
            Integer nestedId = qNameToId.get(nestedQn);
            Integer outerId = qNameToId.get(outerQn);
            if (nestedId == null || outerId == null) continue;
            if (targetIdToCallers != null) {
                Set<Integer> callers = targetIdToCallers.remove(nestedId);
                if (callers != null) {
                    targetIdToCallers.computeIfAbsent(outerId, k -> new LinkedHashSet<>()).addAll(callers);
                }
            }
            if (refToCallerIds != null) {
                Set<Integer> callers = refToCallerIds.remove(nestedQn);
                if (callers != null) {
                    refToCallerIds.computeIfAbsent(outerQn, k -> new LinkedHashSet<>()).addAll(callers);
                }
            }
        }
    }

    /**
     * Для top-level outer-классов сворачивает «внутренние» nested до сигнатуры,
     * кроме типов из {@code expandNestedQNames} (полное дерево внутри outer).
     */
    private ClassContext applyNestedVisibility(
            ClassContext ctx,
            List<ClassStructure> allParsed,
            Map<String, String> nestedToRootTopLevel,
            Set<String> expandNested) {
        if (nestedToRootTopLevel.containsKey(ctx.name())) {
            return ctx;
        }
        ClassStructure cs = findInAllParsed(allParsed, ctx.name());
        if (cs == null) return ctx;
        return withPrunedStructure(ctx, cs, expandNested);
    }

    private ClassContext withPrunedStructure(
            ClassContext ctx,
            ClassStructure rootCs,
            Set<String> expandNested) {
        if (ctx instanceof UnchangedClassContext u) {
            List<StructureNode> pruned = nodeMapper.pruneInternalNested(
                    u.structure(), rootCs, expandNested);
            if (Objects.equals(pruned, u.structure())) return ctx;
            return new UnchangedClassContext(
                    u.id(), u.name(), u.level(), u.callerIds(), u.module(), pruned);
        }
        ModifiedClassContext m = (ModifiedClassContext) ctx;
        List<StructureNode> prunedSrc = m.structureSource() != null
                ? nodeMapper.pruneInternalNested(m.structureSource(), rootCs, expandNested)
                : null;
        List<StructureNode> prunedTgt = m.structureTarget() != null
                ? nodeMapper.pruneInternalNested(m.structureTarget(), rootCs, expandNested)
                : null;
        if (Objects.equals(prunedSrc, m.structureSource())
                && Objects.equals(prunedTgt, m.structureTarget())) {
            return ctx;
        }
        return new ModifiedClassContext(
                m.id(), m.name(), m.level(), m.callerIds(), m.module(), prunedSrc, prunedTgt);
    }

    private void mergeCallerIds(
            List<ClassContext> allContexts,
            String qName,
            Set<Integer> newCallers,
            Map<String, Integer> qNameToId) {
        if (newCallers.isEmpty()) return;
        Integer id = qNameToId.get(qName);
        if (id == null) return;
        for (int i = 0; i < allContexts.size(); i++) {
            ClassContext ctx = allContexts.get(i);
            if (ctx.id() != id) continue;
            Set<Integer> merged = new LinkedHashSet<>(ctx.callerIds());
            merged.addAll(newCallers);
            if (!merged.equals(ctx.callerIds())) {
                allContexts.set(i, withCallerIds(ctx, merged));
            }
            return;
        }
    }

    private static ClassContext withCallerIds(ClassContext ctx, Set<Integer> callerIds) {
        if (ctx instanceof UnchangedClassContext u) {
            return new UnchangedClassContext(
                    u.id(), u.name(), u.level(), callerIds, u.module(), u.structure());
        }
        ModifiedClassContext m = (ModifiedClassContext) ctx;
        return new ModifiedClassContext(
                m.id(), m.name(), m.level(), callerIds, m.module(),
                m.structureSource(), m.structureTarget());
    }

    // ── fetchAndParse ────────────────────────────────────────────────────────────────

    private DepthResult fetchAndParse(
            String qName,
            Set<Integer> callerIds,
            Map<String, List<String>> fileIndex,
            Map<String, Path> dependencySources,
            BiFunction<String, List<String>, Optional<String>> wildcardResolver,
            ContextRequest request,
            String sourceBranch,
            int depth,
            AtomicInteger totalRequested,
            AtomicInteger totalResolved,
            AtomicInteger totalSkipped) {

        totalRequested.incrementAndGet();

        // 1. Jar — кэш до чтения sources.jar
        String jarCandidate = qName;
        while (!jarCandidate.isEmpty()) {
            Path jarPath = dependencySources.get(jarCandidate);
            if (jarPath != null) {
                String module = jarSource(jarPath);
                Optional<DepthResult> cached = depthResultFromCache(module, qName, callerIds, depth);
                if (cached.isPresent()) {
                    totalResolved.incrementAndGet();
                    return cached.get();
                }
                Optional<String> contentOpt = classNameExtractor.extractSourceFile(jarPath, jarCandidate);
                if (contentOpt.isPresent()) {
                    totalResolved.incrementAndGet();
                    String syntheticPath = jarCandidate.replace('.', '/') + ".java";
                    if (!jarCandidate.equals(qName)) {
                        log.debug("Type '{}' resolved via outer class '{}' from sources.jar ({})",
                                qName, jarCandidate, module);
                    } else {
                        log.debug("Resolved '{}' from sources.jar ({})", qName, module);
                    }
                    return buildDepthResult(
                            qName, callerIds, module, contentOpt.get(), syntheticPath, depth, wildcardResolver);
                }
            }
            int lastDot = jarCandidate.lastIndexOf('.');
            if (lastDot < 0) break;
            jarCandidate = jarCandidate.substring(0, lastDot);
        }

        // 2. Repo — кэш до HTTP, если путь известен из индекса
        Optional<DepthResult> repoResult = fetchFromRepo(
                qName, callerIds, depth, fileIndex, wildcardResolver,
                request.gitlabUrl(), request.token(), request.projectId(), sourceBranch);
        if (repoResult.isPresent()) {
            totalResolved.incrementAndGet();
            return repoResult.get();
        }

        totalSkipped.incrementAndGet();
        log.debug("Type '{}' not found in repo or dependency sources — skipping", qName);
        return null;
    }

    private Optional<DepthResult> depthResultFromCache(
            String module, String qName, Set<Integer> callerIds, int depth) {
        return parseCache.get(module, qName)
                .filter(ParseCacheEntry::hasParsedStructures)
                .map(e -> {
                    log.debug("Parse cache hit (before I/O): {} in {}", qName, module);
                    return new DepthResult(qName, callerIds, e.parsed(), e.fileNodes(), module, depth);
                });
    }

    private Optional<DepthResult> fetchFromRepo(
            String qName,
            Set<Integer> callerIds,
            int depth,
            Map<String, List<String>> fileIndex,
            BiFunction<String, List<String>, Optional<String>> wildcardResolver,
            String gitlabUrl,
            String token,
            String projectId,
            String branch) {

        Optional<String> repoPath = resolveRepoFilePath(qName, fileIndex);
        if (repoPath.isPresent()) {
            String module = repoSource(repoPath.get());
            Optional<DepthResult> cached = depthResultFromCache(module, qName, callerIds, depth);
            if (cached.isPresent()) {
                return cached;
            }
            return gitLabService.readFileContent(gitlabUrl, token, projectId, branch, repoPath.get())
                    .map(content -> buildDepthResult(
                            qName, callerIds, module, content, repoPath.get(), depth, wildcardResolver));
        }

        return findTopLevelTypeInPackage(qName, callerIds, depth, fileIndex, wildcardResolver,
                gitlabUrl, token, projectId, branch);
    }

    /**
     * Путь к .java в репозитории (без чтения содержимого), с подъёмом по outer-классам.
     */
    private Optional<String> resolveRepoFilePath(String qName, Map<String, List<String>> fileIndex) {
        Optional<String> path = gitLabService.findJavaFileByQualifiedName(fileIndex, qName);
        if (path.isPresent()) {
            return path;
        }
        String candidate = qName;
        while (candidate.contains(".")) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            path = gitLabService.findJavaFileByQualifiedName(fileIndex, candidate);
            if (path.isPresent()) {
                log.debug("Type '{}' resolved via outer class path '{}'", qName, candidate);
                return path;
            }
        }
        return Optional.empty();
    }

    /**
     * Парсит файл одним проходом или восстанавливает полный результат из кэша.
     */
    private DepthResult buildDepthResult(
            String qName,
            Set<Integer> callerIds,
            String module,
            String content,
            String filePath,
            int depth,
            BiFunction<String, List<String>, Optional<String>> wildcardResolver) {
        Optional<DepthResult> cached = depthResultFromCache(module, qName, callerIds, depth);
        if (cached.isPresent()) {
            return cached.get();
        }

        ParsedJavaFile file = sourceParseService.parse(content, filePath, depth, wildcardResolver);
        return new DepthResult(qName, callerIds, file.structures(), file.nodes(), module, depth);
    }

    /** Кэш только для jar ({@code groupId:artifactId:version}), не для {@code src/main} / {@code src/test}. */
    private void storeParseCache(
            String module,
            String qualifiedName,
            List<ClassStructure> parsed,
            List<StructureNode> fileNodes,
            ClassContext ctx) {
        if (!ClassContextParseCache.isCacheableModule(module)) {
            return;
        }
        parseCache.put(module, qualifiedName,
                new ParseCacheEntry(parsed, fileNodes, ParseCacheEntry.toTemplate(ctx)));
    }

    // ── awaitAll ────────────────────────────────────────────────────────────────────

    private <T> List<T> awaitAll(List<CompletableFuture<T>> futures) {
        List<T> results = new ArrayList<>(futures.size());
        for (CompletableFuture<T> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting async task", e);
            } catch (ExecutionException e) {
                log.warn("Async task failed", e.getCause());
                results.add(null);
            }
        }
        return results;
    }

    // ── Source label helpers ──────────────────────────────────────────────────────────

    private static String repoSource(String filePath) {
        return filePath.startsWith("src/test/") ? "src/test" : "src/main";
    }

    private static String jarSource(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        DependencyCoordinate coord = DependencyCoordinate.fromLocalFileName(fileName);
        return coord != null ? coord.toString() : fileName;
    }

    // ── Repo source lookup ──────────────────────────────────────────────────────────

    /**
     * Ищет top-level тип по qualified name среди всех .java файлов его пакета.
     * Сначала проверяет кэш по пути файла (без HTTP).
     */
    private Optional<DepthResult> findTopLevelTypeInPackage(
            String qName,
            Set<Integer> callerIds,
            int depth,
            Map<String, List<String>> fileIndex,
            BiFunction<String, List<String>, Optional<String>> wildcardResolver,
            String gitlabUrl,
            String token,
            String projectId,
            String branch) {
        int lastDot = qName.lastIndexOf('.');
        if (lastDot < 0) return Optional.empty();
        String packageName = qName.substring(0, lastDot);
        String simpleName = qName.substring(lastDot + 1);

        for (String candidatePath : gitLabService.listJavaFilesInPackage(fileIndex, packageName)) {
            String module = repoSource(candidatePath);
            Optional<DepthResult> cached = depthResultFromCache(module, qName, callerIds, depth);
            if (cached.isPresent()) {
                return cached;
            }
            Optional<String> content = gitLabService.readFileContent(
                    gitlabUrl, token, projectId, branch, candidatePath);
            if (content.isPresent()
                    && structureParser.containsTopLevelType(content.get(), simpleName)) {
                log.debug("Type '{}' resolved via package scan in {}", qName, candidatePath);
                return Optional.of(buildDepthResult(
                        qName, callerIds, module, content.get(), candidatePath, depth, wildcardResolver));
            }
        }
        return Optional.empty();
    }

    // ── Wildcard resolver ───────────────────────────────────────────────────────────

    private BiFunction<String, List<String>, Optional<String>> buildWildcardResolver(
            Map<String, List<String>> fileIndex,
            Map<String, Path> dependencySources) {
        return (simpleName, wildcardPackages) -> {
            List<String> candidates = new ArrayList<>();
            String fileName = simpleName + ".java";
            for (String path : fileIndex.getOrDefault(fileName, List.of())) {
                String dirPath = path.contains("/")
                        ? path.substring(0, path.lastIndexOf('/'))
                        : "";
                String pkg = dirPathToPackage(dirPath);
                if (wildcardPackages.contains(pkg))
                    candidates.add(pkg + "." + simpleName);
            }
            if (candidates.isEmpty()) {
                String suffix = "." + simpleName;
                for (String qName : dependencySources.keySet()) {
                    if (qName.endsWith(suffix)) {
                        String pkg = qName.substring(0, qName.length() - suffix.length());
                        if (wildcardPackages.contains(pkg))
                            candidates.add(qName);
                    }
                }
            }
            if (candidates.size() == 1) return Optional.of(candidates.get(0));
            if (candidates.size() > 1)
                log.warn("Ambiguous wildcard resolution for '{}': candidates={} wildcards={}",
                        simpleName, candidates, wildcardPackages);
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

    // ── collectRawRefs / collectRefToCallerIds / resolveRef ─────────────────────

    private Set<UnresolvedTypeRef> collectRawRefs(List<ClassStructure> classes) {
        Set<UnresolvedTypeRef> refs = new LinkedHashSet<>();
        for (ClassStructure cs : classes) refs.addAll(structureParser.collectReferencedTypes(cs));
        return refs;
    }

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
                if (callerId != null) result.get(resolved).add(callerId);
            }
        }
        return result;
    }

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
                if (wildcards.contains(pkg)) candidates.add(qName);
            }
        }
        if (candidates.size() == 1) {
            log.debug("Post-resolved '{}' → '{}' via wildcardImports={}",
                    simpleName, candidates.get(0), wildcards);
            return candidates.get(0);
        }
        if (candidates.size() > 1)
            log.warn("Ambiguous post-resolution for '{}': candidates={}", simpleName, candidates);
        return simpleName;
    }
}
