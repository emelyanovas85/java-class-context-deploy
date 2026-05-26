package ru.kalinin.context.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.kalinin.context.dependency.DependencyClassNameExtractor;
import ru.kalinin.context.dependency.DependencyCoordinate;
import ru.kalinin.context.exception.MergeRequestAlreadyMergedException;
import ru.kalinin.context.model.*;
import ru.kalinin.context.parser.JavaStructureParser;
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
 *   <li>Уровень 0: fetch + parse + map параллельно; merge однопоточно.</li>
 *   <li>Уровни 1..depth: волновой BFS — все типы волны fetch+parse+map параллельно;
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
    private final DependencyContextService dependencyContextService;
    private final DependencyClassNameExtractor classNameExtractor;
    private final ExecutorService ioExecutor;

    public ContextBuilderService(GitLabService gitLabService,
                                 JavaStructureParser structureParser,
                                 StructureNodeMapper nodeMapper,
                                 DependencyContextService dependencyContextService,
                                 DependencyClassNameExtractor classNameExtractor,
                                 @Qualifier("ioExecutor") ExecutorService ioExecutor) {
        this.gitLabService = gitLabService;
        this.structureParser = structureParser;
        this.nodeMapper = nodeMapper;
        this.dependencyContextService = dependencyContextService;
        this.classNameExtractor = classNameExtractor;
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

    private record DepthResult(
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
                    List<ClassStructure> parsed = src
                            .map(s -> structureParser.parse(s, filePath, 0, wildcardResolver))
                            .orElse(List.of());
                    List<StructureNode> srcNodes = src
                            .map(s -> nodeMapper.map(s, filePath))
                            .orElse(List.of());
                    List<StructureNode> tgtNodes = tgt
                            .map(s -> nodeMapper.map(s, filePath))
                            .orElse(null);
                    return new Level0Result(filePath, src, tgt, parsed, srcNodes, tgtNodes);
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
                if (processedQNames.add(cs.qualifiedName())) {
                    int id = idCounter.getAndIncrement();
                    qNameToId.put(cs.qualifiedName(), id);
                    level0.add(cs);
                    allContexts.add(ClassContext.of(
                            id, Set.of(), cs.qualifiedName(), 0, src0, r.srcNodes(), r.tgtNodes()));
                    registerNestedClasses(cs, 0, src0, r.srcNodes(), r.tgtNodes(),
                            processedQNames, qNameToId, idCounter, allContexts, level0);
                }
            }
        }

        // ── Уровни 1..depth ─────────────────────────────────────────────────────────────
        List<ClassStructure> currentLevel = level0;
        List<ClassStructure> allParsed = new ArrayList<>(level0);

        for (int depth = 1; depth <= request.depth(); depth++) {
            Map<String, Set<Integer>> refToCallerIds =
                    collectRefToCallerIds(currentLevel, processedQNames, qNameToId);
            List<String> wave = refToCallerIds.keySet().stream()
                    .filter(n -> !processedQNames.contains(n))
                    .toList();
            if (wave.isEmpty()) break;

            log.debug("Depth {}: resolving {} types in parallel", depth, wave.size());
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
            for (DepthResult r : awaitAll(futures)) {
                if (r == null || r.parsed().isEmpty()) continue;
                for (ClassStructure cs : r.parsed()) {
                    if (processedQNames.add(cs.qualifiedName()) ||
                            cs.qualifiedName().equals(r.qName())) {
                        int id = idCounter.getAndIncrement();
                        qNameToId.put(cs.qualifiedName(), id);
                        nextLevel.add(cs);
                        allContexts.add(ClassContext.of(
                                id, r.callerIds(), cs.qualifiedName(),
                                r.depth(), r.source(), r.nodes(), r.nodes()));
                        registerNestedClasses(cs, r.depth(), r.source(), r.nodes(), r.nodes(),
                                processedQNames, qNameToId, idCounter, allContexts, nextLevel);
                    }
                }
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
        List<String> finalWave = finalRefToCallerIds.keySet().stream()
                .filter(n -> !processedQNames.contains(n))
                .toList();

        if (!finalWave.isEmpty()) {
            log.info("Final pass: resolving {} previously unresolved types in parallel", finalWave.size());
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
            for (DepthResult r : awaitAll(finalFutures)) {
                if (r == null || r.parsed().isEmpty()) continue;
                for (ClassStructure cs : r.parsed()) {
                    if (processedQNames.add(cs.qualifiedName()) ||
                            cs.qualifiedName().equals(r.qName())) {
                        int id = idCounter.getAndIncrement();
                        qNameToId.put(cs.qualifiedName(), id);
                        finalLevel.add(cs);
                        allContexts.add(ClassContext.of(
                                id, r.callerIds(), cs.qualifiedName(),
                                r.depth(), r.source(), r.nodes(), r.nodes()));
                        registerNestedClasses(cs, r.depth(), r.source(), r.nodes(), r.nodes(),
                                processedQNames, qNameToId, idCounter, allContexts, finalLevel);
                    }
                }
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

        return new ContextResponse(mrInfo, allContexts, request.depth(), allContexts.size());
    }

    // ── registerNestedClasses ─────────────────────────────────────────────────────

    /**
     * Регистрирует все вложенные классы {@code cs} как полноценные {@link ClassContext}
     * с тем же {@code depth} и {@code source}, что и родительский класс.
     * Для каждого nested класса извлекается его собственное поддерево {@link StructureNode}
     * через {@link StructureNodeMapper#findNestedTypeNodes}.
     * Каждый nested класс добавляется в {@code nextLevel}, чтобы его собственные
     * ссылки участвовали в следующей волне BFS.
     * Рекурсивно обрабатывает вложенные вложенных.
     */
    private void registerNestedClasses(
            ClassStructure cs,
            int depth,
            String source,
            List<StructureNode> srcNodes,
            List<StructureNode> tgtNodes,
            Set<String> processedQNames,
            Map<String, Integer> qNameToId,
            AtomicInteger idCounter,
            List<ClassContext> allContexts,
            List<ClassStructure> nextLevel) {

        for (ClassStructure nested : cs.nestedClasses()) {
            if (processedQNames.add(nested.qualifiedName())) {
                int id = idCounter.getAndIncrement();
                qNameToId.put(nested.qualifiedName(), id);
                nextLevel.add(nested);

                // Извлекаем поддерево только для этого nested класса
                String simpleName = nested.simpleName();
                List<StructureNode> nestedSrcNodes = nodeMapper.findNestedTypeNodes(srcNodes, simpleName);
                List<StructureNode> nestedTgtNodes = tgtNodes != null
                        ? nodeMapper.findNestedTypeNodes(tgtNodes, simpleName)
                        : null;

                allContexts.add(ClassContext.of(
                        id, Set.of(), nested.qualifiedName(),
                        depth, source, nestedSrcNodes, nestedTgtNodes));
                log.debug("Registered nested class '{}' at depth {}", nested.qualifiedName(), depth);

                // Рекурсия для вложенных вложенных
                registerNestedClasses(nested, depth, source, nestedSrcNodes, nestedTgtNodes,
                        processedQNames, qNameToId, idCounter, allContexts, nextLevel);
            }
        }
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

        // 1. Поиск в repo (с подъёмом по outer классам — уже внутри findRepoSourceForType)
        Optional<Map.Entry<String, String>> repoOpt =
                findRepoSourceForType(qName, fileIndex,
                        request.gitlabUrl(), request.token(),
                        request.projectId(), sourceBranch);
        if (repoOpt.isPresent()) {
            totalResolved.incrementAndGet();
            String filePath = repoOpt.get().getKey();
            String content  = repoOpt.get().getValue();
            return new DepthResult(
                    qName, callerIds,
                    structureParser.parse(content, filePath, depth, wildcardResolver),
                    nodeMapper.map(content, filePath),
                    repoSource(filePath),
                    depth);
        }

        // 2. Поиск в jar — с подъёмом по outer классам
        //    Например: userInterface.ComplexTable.Row → userInterface.ComplexTable
        String jarCandidate = qName;
        while (!jarCandidate.isEmpty()) {
            Path jarPath = dependencySources.get(jarCandidate);
            if (jarPath != null) {
                Optional<String> contentOpt = classNameExtractor.extractSourceFile(jarPath, jarCandidate);
                if (contentOpt.isPresent()) {
                    totalResolved.incrementAndGet();
                    String content       = contentOpt.get();
                    String syntheticPath = jarCandidate.replace('.', '/') + ".java";
                    if (!jarCandidate.equals(qName)) {
                        log.debug("Type '{}' resolved via outer class '{}' from sources.jar ({})",
                                qName, jarCandidate, jarSource(jarPath));
                    } else {
                        log.debug("Resolved '{}' from sources.jar ({})", qName, jarSource(jarPath));
                    }
                    return new DepthResult(
                            qName, callerIds,
                            structureParser.parse(content, syntheticPath, depth, wildcardResolver),
                            nodeMapper.map(content, syntheticPath),
                            jarSource(jarPath),
                            depth);
                }
            }
            int lastDot = jarCandidate.lastIndexOf('.');
            if (lastDot < 0) break;
            jarCandidate = jarCandidate.substring(0, lastDot);
        }

        totalSkipped.incrementAndGet();
        log.debug("Type '{}' not found in repo or dependency sources — skipping", qName);
        return null;
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

    private Optional<Map.Entry<String, String>> findRepoSourceForType(
            String qName,
            Map<String, List<String>> fileIndex,
            String gitlabUrl, String token, String projectId, String branch) {

        Optional<Map.Entry<String, String>> exact =
                readRepoFile(qName, fileIndex, gitlabUrl, token, projectId, branch);
        if (exact.isPresent()) return exact;

        String candidate = qName;
        while (candidate.contains(".")) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            Optional<Map.Entry<String, String>> found =
                    readRepoFile(candidate, fileIndex, gitlabUrl, token, projectId, branch);
            if (found.isPresent()) {
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
