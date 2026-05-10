package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.kalinin.context.model.*;
import ru.kalinin.context.parser.JavaStructureParser;

import java.util.*;

/**
 * Основной сервис: строит многоуровневый контекст для мёрж-реквеста.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>Уровень 0: читаем изменённые .java файлы, парсим, строим {@link ClassStructure}.</li>
 *   <li>Уровень N (N ≥ 1): собираем все типы, упомянутые на уровне N-1,
 *       ищем их в репозитории GitLab, парсим и добавляем к результату.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextBuilderService {

    private final GitLabService gitLabService;
    private final JavaStructureParser structureParser;

    /**
     * Точка входа: строит полный контекст.
     */
    public ContextResponse buildContext(ContextRequest request) {
        MergeRequestInfo mrInfo = gitLabService.getMergeRequestInfo(
                request.gitlabUrl(), request.token(),
                request.projectId(), request.mergeRequestIid());

        String branch = mrInfo.sourceBranch();

        List<ClassStructure> allClasses = new ArrayList<>();
        Set<String> processedQNames = new LinkedHashSet<>();

        List<ClassStructure> level0 = parseFiles(
                request, branch, mrInfo.changedFiles(), 0, processedQNames);
        allClasses.addAll(level0);

        List<ClassStructure> currentLevel = level0;
        for (int depth = 1; depth < request.depth(); depth++) {
            Set<String> referencedTypes = collectAllReferencedTypes(currentLevel);
            referencedTypes.removeAll(processedQNames);
            if (referencedTypes.isEmpty()) break;

            log.debug("Depth {}: resolving {} referenced types", depth, referencedTypes.size());

            List<String> filePaths = resolveTypesToFilePaths(request, branch, referencedTypes);

            int finalDepth = depth;
            List<ClassStructure> nextLevel = parseFiles(
                    request, branch, filePaths, finalDepth, processedQNames);
            allClasses.addAll(nextLevel);
            currentLevel = nextLevel;
        }

        return new ContextResponse(mrInfo, allClasses, request.depth(), allClasses.size());
    }

    private List<ClassStructure> parseFiles(
            ContextRequest request, String branch,
            List<String> filePaths, int contextLevel,
            Set<String> processedQNames) {

        List<ClassStructure> result = new ArrayList<>();
        for (String filePath : filePaths) {
            log.debug("Reading file: {}", filePath);
            gitLabService.readFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), branch, filePath
            ).ifPresent(content -> {
                List<ClassStructure> parsed =
                        structureParser.parse(content, filePath, contextLevel);
                parsed.forEach(cs -> {
                    if (!processedQNames.contains(cs.qualifiedName())) {
                        processedQNames.add(cs.qualifiedName());
                        result.add(cs);
                        addNestedQNames(cs, processedQNames);
                    }
                });
            });
        }
        return result;
    }

    private void addNestedQNames(ClassStructure cs, Set<String> processedQNames) {
        cs.nestedClasses().forEach(nc -> {
            processedQNames.add(nc.qualifiedName());
            addNestedQNames(nc, processedQNames);
        });
    }

    private Set<String> collectAllReferencedTypes(List<ClassStructure> classes) {
        Set<String> types = new LinkedHashSet<>();
        for (ClassStructure cs : classes) {
            types.addAll(structureParser.collectReferencedTypes(cs));
        }
        return types;
    }

    private List<String> resolveTypesToFilePaths(
            ContextRequest request, String branch, Set<String> qualifiedNames) {

        Set<String> resolvedPaths = new LinkedHashSet<>();
        for (String qName : qualifiedNames) {
            gitLabService.findJavaFileByQualifiedName(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), qName, branch
            ).ifPresent(resolvedPaths::add);
        }
        return new ArrayList<>(resolvedPaths);
    }
}
