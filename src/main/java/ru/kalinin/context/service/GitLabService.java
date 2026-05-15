package ru.kalinin.context.service;

import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.RepositoryFile;
import org.gitlab4j.api.models.TreeItem;
import org.springframework.stereotype.Service;
import ru.kalinin.context.model.CommitInfo;
import ru.kalinin.context.model.MergeRequestInfo;

import java.util.*;

/**
 * Взаимодействие с GitLab через gitlab4j-api.
 *
 * <h2>Файловый индекс для резолвинга зависимостей</h2>
 * <p>Индекс строится на основе <b>target-ветки</b> (один запрос
 * {@code repository/tree?recursive=true}) с последующим наложением
 * патча из diff MR:
 * <ul>
 *   <li>Добавленные файлы ({@code newFile=true}) — добавляются в индекс.</li>
 *   <li>Удалённые файлы ({@code deletedFile=true}) — остаются в индексе
 *       (намеренно: позволяет резолвить типы, которые удалены в source,
 *       но на которые всё ещё есть ссылки).</li>
 *   <li>Переименованные ({@code renamedFile=true}) — добавляется {@code newPath};
 *       {@code oldPath} остаётся из target-индекса.</li>
 *   <li>Изменённые — путь не меняется, индекс не трогается.</li>
 * </ul>
 * Индекс кэшируется в памяти по ключу projectId+sourceBranch+targetBranch.
 */
@Slf4j
@Service
public class GitLabService {

    /** Имена файлов сборки, которые мы умеем обрабатывать. */
    private static final Set<String> BUILD_FILE_NAMES = Set.of(
            "build.gradle", "build.gradle.kts", "pom.xml"
    );

    /**
     * Кэш файловых индексов для резолвинга зависимостей.
     * Ключ: "projectId#sourceBranch#targetBranch"
     * Значение: simpleName.java → List<fullPath>
     */
    private final Map<String, Map<String, List<String>>> mergedIndexCache = new HashMap<>();

    /**
     * Кэш индексов отдельных веток (используется для findBuildFiles).
     * Ключ: "projectId#branch"
     */
    private final Map<String, Map<String, List<String>>> branchIndexCache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Получить метаданные мёрж-реквеста.
     */
    public MergeRequestInfo getMergeRequestInfo(String gitlabUrl, String token,
                                                String projectId, long mrIid) {
        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            MergeRequest mr = api.getMergeRequestApi()
                    .getMergeRequest(projectId, mrIid);

            List<CommitInfo> commits = api.getMergeRequestApi()
                    .getCommits(projectId, mrIid)
                    .stream()
                    .map(c -> new CommitInfo(
                            c.getId(),
                            c.getTitle(),
                            c.getAuthorName(),
                            c.getAuthorEmail(),
                            c.getCreatedAt() != null ? c.getCreatedAt().toString() : null))
                    .toList();

            List<Diff> diffs = api.getMergeRequestApi()
                    .getMergeRequestChanges(projectId, mrIid)
                    .getChanges();

            // changedFiles — только не-удалённые .java файлы (для уровня 0)
            List<String> changedFiles = diffs.stream()
                    .filter(d -> !d.getDeletedFile())
                    .map(Diff::getNewPath)
                    .filter(p -> p.endsWith(".java"))
                    .toList();

            return new MergeRequestInfo(
                    mr.getIid(),
                    mr.getTitle(),
                    mr.getState() != null ? mr.getState().toString() : null,
                    mr.getSourceBranch(),
                    mr.getTargetBranch(),
                    mr.getAuthor() != null ? mr.getAuthor().getUsername() : null,
                    commits,
                    changedFiles,
                    diffs
            );
        } catch (GitLabApiException e) {
            throw new RuntimeException("GitLab API error: " + e.getMessage(), e);
        }
    }

    /**
     * Прочитать содержимое Java-файла из репозитория GitLab.
     * Возвращает {@code Optional.empty()} если файл не найден или не .java
     */
    public Optional<String> readFileContent(String gitlabUrl, String token, String projectId,
                                            String branch, String filePath) {
        if (!filePath.endsWith(".java")) {
            return Optional.empty();
        }
        return readRawFileContent(gitlabUrl, token, projectId, branch, filePath);
    }

    /**
     * Прочитать содержимое произвольного файла без проверки расширения.
     * Используется для чтения файлов сборки (*.gradle, pom.xml и т.д.).
     *
     * @return содержимое файла или {@code Optional.empty()} если файл не найден (404)
     */
    public Optional<String> readRawFileContent(String gitlabUrl, String token, String projectId,
                                               String branch, String filePath) {
        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            RepositoryFile file = api.getRepositoryFileApi()
                    .getFile(projectId, filePath, branch);
            String content = new String(
                    java.util.Base64.getDecoder().decode(file.getContent()));
            return Optional.of(content);
        } catch (GitLabApiException e) {
            if (e.getHttpStatus() == 404) {
                return Optional.empty();
            }
            throw new RuntimeException(
                    "Error reading file " + filePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Найти все файлы сборки (build.gradle, build.gradle.kts, pom.xml)
     * в репозитории GitLab для указанной ветки.
     */
    public List<String> findBuildFiles(String gitlabUrl, String token,
                                       String projectId, String branch) {
        Map<String, List<String>> index = getOrBuildBranchIndex(gitlabUrl, token, projectId, branch);
        List<String> result = new ArrayList<>();
        for (String buildFileName : BUILD_FILE_NAMES) {
            List<String> paths = index.getOrDefault(buildFileName, List.of());
            result.addAll(paths);
        }
        log.debug("findBuildFiles project={} branch={}: found {}", projectId, branch, result);
        return result;
    }

    /**
     * Найти путь к .java-файлу по qualified name класса.
     *
     * <p>Использует мёрженный индекс: target-ветка + патч из diff MR.
     * Это позволяет резолвить:
     * <ul>
     *   <li>классы, добавленные в source (есть в diff, нет в target);</li>
     *   <li>классы, удалённые в source (нет в source, но есть в target —
     *       на случай когда на удалённый тип ещё есть ссылки в коде).</li>
     * </ul>
     *
     * @param qualifiedName полное имя класса, например {@code com.example.Foo}
     * @param sourceBranch  ветка с изменениями
     * @param targetBranch  целевая ветка
     * @param mrDiffs       список diff-записей MR (из {@link MergeRequestInfo#diffs()})
     */
    public Optional<String> findJavaFileByQualifiedName(String gitlabUrl, String token,
                                                        String projectId, String qualifiedName,
                                                        String sourceBranch, String targetBranch,
                                                        List<Diff> mrDiffs) {
        Map<String, List<String>> index =
                getOrBuildMergedIndex(gitlabUrl, token, projectId, sourceBranch, targetBranch, mrDiffs);

        String simpleName = qualifiedName.contains(".")
                ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                : qualifiedName;
        if (simpleName.contains("$")) {
            simpleName = simpleName.substring(0, simpleName.indexOf('$'));
        }
        String fileName = simpleName + ".java";

        List<String> candidates = index.getOrDefault(fileName, List.of());
        if (candidates.isEmpty()) {
            log.debug("No file found in merged index for class: {}", qualifiedName);
            return Optional.empty();
        }

        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }

        String packageSuffix = qualifiedName.replace('.', '/') + ".java";
        Optional<String> exact = candidates.stream()
                .filter(path -> path.endsWith(packageSuffix))
                .findFirst();
        if (exact.isPresent()) return exact;

        Optional<String> mainFirst = candidates.stream()
                .filter(p -> p.contains("src/main/java"))
                .findFirst();
        return mainFirst.isPresent() ? mainFirst : Optional.of(candidates.get(0));
    }

    // -------------------------------------------------------------------------
    // Index — merged (target + MR diff patch)
    // -------------------------------------------------------------------------

    private Map<String, List<String>> getOrBuildMergedIndex(String gitlabUrl, String token,
                                                             String projectId,
                                                             String sourceBranch,
                                                             String targetBranch,
                                                             List<Diff> mrDiffs) {
        String cacheKey = projectId + "#" + sourceBranch + "#" + targetBranch;
        return mergedIndexCache.computeIfAbsent(cacheKey,
                k -> buildMergedIndex(gitlabUrl, token, projectId, targetBranch, mrDiffs));
    }

    /**
     * Строит мёрженный индекс:
     * <ol>
     *   <li>Базовый индекс из target-ветки.</li>
     *   <li>Патч из diff MR: добавленные и переименованные файлы добавляются;
     *       удалённые — намеренно остаются (см. Javadoc класса).</li>
     * </ol>
     */
    private Map<String, List<String>> buildMergedIndex(String gitlabUrl, String token,
                                                        String projectId, String targetBranch,
                                                        List<Diff> mrDiffs) {
        log.info("Building merged file index for project={} targetBranch={}", projectId, targetBranch);

        // шаг 1: базовый индекс из target
        Map<String, List<String>> index = new HashMap<>(buildRawIndex(gitlabUrl, token, projectId, targetBranch));

        // шаг 2: патч из diff MR
        for (Diff diff : mrDiffs) {
            String path = diff.getNewPath();
            if (path == null || !path.endsWith(".java")) continue;

            if (diff.getNewFile() || diff.getRenamedFile()) {
                // добавленный или переименованный — добавляем newPath
                String name = fileName(path);
                index.computeIfAbsent(name, k -> new ArrayList<>()).add(path);
                log.debug("Merged index patch: +{} ({})",
                        path, diff.getNewFile() ? "added" : "renamed");
            }
            // deletedFile=true и изменённые файлы — индекс не трогаем
        }

        log.info("Merged index built: {} unique filenames, project={}", index.size(), projectId);
        return Collections.unmodifiableMap(index);
    }

    // -------------------------------------------------------------------------
    // Index — single branch (used for findBuildFiles)
    // -------------------------------------------------------------------------

    private Map<String, List<String>> getOrBuildBranchIndex(String gitlabUrl, String token,
                                                             String projectId, String branch) {
        String cacheKey = projectId + "#" + branch;
        return branchIndexCache.computeIfAbsent(cacheKey,
                k -> buildRawIndex(gitlabUrl, token, projectId, branch));
    }

    private Map<String, List<String>> buildRawIndex(String gitlabUrl, String token,
                                                     String projectId, String branch) {
        log.info("Building raw file index for project={} branch={}", projectId, branch);
        Map<String, List<String>> index = new HashMap<>();

        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            var pager = api.getRepositoryApi()
                    .getTree(projectId, null, branch, true, 100);

            while (pager.hasNext()) {
                for (TreeItem item : pager.next()) {
                    if (item.getType() == TreeItem.Type.BLOB) {
                        index.computeIfAbsent(item.getName(), k -> new ArrayList<>())
                             .add(item.getPath());
                    }
                }
            }
        } catch (GitLabApiException e) {
            throw new RuntimeException(
                    "Failed to build file index for " + projectId + "@" + branch
                    + ": " + e.getMessage(), e);
        }

        log.info("Raw file index built: {} unique filenames, project={} branch={}",
                index.size(), projectId, branch);
        return index;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
