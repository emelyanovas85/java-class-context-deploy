package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.kalinin.context.exception.MergeRequestAlreadyMergedException;
import ru.kalinin.context.model.*;
import ru.kalinin.context.parser.JavaStructureParser;
import ru.kalinin.context.parser.StructureNodeMapper;

import java.util.*;

/**
 * Основной сервис: строит многоуровневый контекст для мёрж-реквеста.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>Проверка состояния MR: только opened/locked проходят дальше.</li>
 *   <li>Сбор контекста зависимостей: получение списка известных классов
 *       из зависимостей через sources.jar.</li>
 *   <li>Уровень 0: читаем изменённые .java-файлы из source-ветки,
 *       для каждого строим пару structureSource / structureTarget.</li>
 *   <li>Уровни N ≥ 1: собираем все типы из уровня N-1, ищем в репозитории,
 *       строим структуры только из source-ветки.
 *       Типы, известные из зависимостей, в резолвинг не идут.</li>
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

    public ContextResponse buildContext(ContextRequest request) {
        MergeRequestInfo mrInfo = gitLabService.getMergeRequestInfo(
                request.gitlabUrl(), request.token(),
                request.projectId(), request.mergeRequestIid());

        // ── Проверка состояния MR ────────────────────────────────────────────────
        if (!ANALYZABLE_STATES.contains(mrInfo.state())) {
            throw new MergeRequestAlreadyMergedException(
                    request.mergeRequestIid(), mrInfo.state());
        }

        String sourceBranch = mrInfo.sourceBranch();
        String targetBranch = mrInfo.targetBranch();

        // ── Этап 0: сбор контекста зависимостей (перед резолвингом) ──────────────────────
        Set<String> dependencyClassNames = dependencyContextService.collectDependencyClassNames(
                request.gitlabUrl(), request.token(),
                request.projectId(), sourceBranch);
        log.info("Dependency context: {} known external class names", dependencyClassNames.size());

        List<ChangedClassContext> allContexts = new ArrayList<>();
        Set<String> processedQNames = new LinkedHashSet<>();

        // ── Уровень 0: изменённые файлы ──────────────────────────────────────────
        List<ClassStructure> level0 = new ArrayList<>();
        for (String filePath : mrInfo.changedFiles()) {
            log.debug("Level 0: reading {}", filePath);

            Optional<String> sourceContent = gitLabService.readFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), sourceBranch, filePath);

            Optional<String> targetContent = gitLabService.readFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), targetBranch, filePath);

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
                        allContexts.add(ChangedClassContext.of(
                                cs.qualifiedName(), 0, srcNodes, tgtNodes));
                    }
                });
            });
        }

        // ── Уровни 1..depth-1: зависимости ──────────────────────────────────────
        List<ClassStructure> currentLevel = level0;
        for (int depth = 1; depth <= request.depth(); depth++) {
            Set<String> referencedTypes = collectAllReferencedTypes(currentLevel);
            referencedTypes.removeAll(processedQNames);
            referencedTypes.removeAll(dependencyClassNames);
            if (referencedTypes.isEmpty()) break;

            log.debug("Depth {}: resolving {} referenced types", depth, referencedTypes.size());

            List<ClassStructure> nextLevel = new ArrayList<>();
            int finalDepth = depth;

            for (String qName : referencedTypes) {
                gitLabService.findJavaFileByQualifiedName(
                        request.gitlabUrl(), request.token(),
                        request.projectId(), qName, sourceBranch)
                .flatMap(filePath -> gitLabService.readFileContent(
                        request.gitlabUrl(), request.token(),
                        request.projectId(), sourceBranch, filePath)
                        .map(content -> Map.entry(filePath, content)))
                .ifPresent(entry -> {
                    String filePath = entry.getKey();
                    String content  = entry.getValue();
                    List<ClassStructure> parsed =
                            structureParser.parse(content, filePath, finalDepth);
                    List<StructureNode> nodes = nodeMapper.map(content, filePath);

                    parsed.forEach(cs -> {
                        if (!processedQNames.contains(cs.qualifiedName())) {
                            processedQNames.add(cs.qualifiedName());
                            nextLevel.add(cs);
                            addNestedQNames(cs, processedQNames);
                            allContexts.add(ChangedClassContext.of(
                                    cs.qualifiedName(), finalDepth, nodes, nodes));
                        }
                    });
                });
            }

            currentLevel = nextLevel;
        }

        return new ContextResponse(mrInfo, allContexts, request.depth(), allContexts.size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

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
