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
 * <p>Алгоритм:
 * <ol>
 *   <li>Проверка состояния MR: только opened/locked проходят дальше.</li>
 *   <li>Построение мёрженного файлового индекса: target + patch из diff MR.</li>
 *   <li>Сбор карты зависимостей: qualified name → путь к jar на диске.</li>
 *   <li>Уровень 0: читаем изменённые .java-файлы из source-ветки,
 *       для каждого строим пару structureSource / structureTarget.</li>
 *   <li>Уровни N ≥ 1: зависимости, не входящие в diff MR.
 *       Сначала ищем в репозитории (fileIndex), затем — в sources.jar на диске.
 *       Если зависимость сама изменилась в MR — она уже обработана на уровне 0.</li>
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

        // Индекс строится один раз и передаётся всем, кому он нужен
        Map<String, List<String>> fileIndex = gitLabService.buildMergedFileIndex(
                request.gitlabUrl(), request.token(),
                request.projectId(), targetBranch, mrInfo.diffs()
        );

        // Карта: qualified name → путь к jar на диске.
        // Байты в памяти не удерживаются; jar кэшируется на диске между запросами.
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
                // 1. Ищем в репозитории проекта
                Optional<Map.Entry<String, String>> repoSource =
                        gitLabService.findJavaFileByQualifiedName(fileIndex, qName)
                                .flatMap(filePath -> gitLabService.readFileContent(
                                        request.gitlabUrl(), request.token(),
                                        request.projectId(), sourceBranch, filePath)
                                        .map(content -> Map.entry(filePath, content)));

                if (repoSource.isPresent()) {
                    String filePath = repoSource.get().getKey();
                    String content = repoSource.get().getValue();
                    List<ClassStructure> parsed =
                            structureParser.parse(content, filePath, finalDepth);
                    List<StructureNode> nodes = nodeMapper.map(content, filePath);

                    parsed.forEach(cs -> {
                        if (!processedQNames.contains(cs.qualifiedName())) {
                            processedQNames.add(cs.qualifiedName());
                            nextLevel.add(cs);
                            addNestedQNames(cs, processedQNames);
                            allContexts.add(ClassContext.of(
                                    cs.qualifiedName(), finalDepth, nodes, nodes));
                        }
                    });
                    continue;
                }

                // 2. Ищем в sources.jar зависимостей (jar лежит на диске)
                Path jarPath = dependencySources.get(qName);
                if (jarPath == null) {
                    log.debug("Type {} not found in repo or dependency sources — skipping", qName);
                    continue;
                }

                classNameExtractor.extractSourceFile(jarPath, qName).ifPresent(content -> {
                    String syntheticPath = qName.replace('.', '/') + ".java";
                    List<ClassStructure> parsed =
                            structureParser.parse(content, syntheticPath, finalDepth);
                    List<StructureNode> nodes = nodeMapper.map(content, syntheticPath);

                    parsed.forEach(cs -> {
                        if (!processedQNames.contains(cs.qualifiedName())) {
                            processedQNames.add(cs.qualifiedName());
                            nextLevel.add(cs);
                            addNestedQNames(cs, processedQNames);
                            allContexts.add(ClassContext.of(
                                    cs.qualifiedName(), finalDepth, nodes, nodes));
                        }
                    });
                    log.debug("Resolved {} from sources.jar on disk", qName);
                });
            }

            currentLevel = nextLevel;
        }

        return new ContextResponse(mrInfo, allContexts, request.depth(), allContexts.size());
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
