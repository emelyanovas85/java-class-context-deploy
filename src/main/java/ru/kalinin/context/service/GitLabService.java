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
 * <p>Мёрженный индекс строится на основе <b>target-ветки</b> (один запрос
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
 * Индекс не кэшируется в поле сервиса: он строится один раз в рамках buildContext()
 * и передаётся дальше как локальная переменная.
 */
@Slf4j
@Service
public class GitLabService {

    /** Имена файлов сборки, которые мы умеем обрабатывать. */
    private static final Set<String> BUILD_FILE_NAMES = Set.of(
            "build.gradle", "build.gradle.kts", "pom.xml"
    );

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
                    .filter(Objects::nonNull)
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
     * в уже готовом индексе.
     *
     * @param fileIndex мёрженный файловый индекс из {@link #buildMergedFileIndex}
     */
    public List<String> findBuildFiles(Map<String, List<String>> fileIndex) {
        List<String> result = new ArrayList<>();
        for (String buildFileName : BUILD_FILE_NAMES) {
            List<String> paths = fileIndex.getOrDefault(buildFileName, List.of());
            result.addAll(paths);
        }
        log.debug("findBuildFiles: found {}", result);
        return result;
    }

    /**
     * Построить мёрженный индекс для резолвинга зависимостей.
     *
     * <p>Базой служит target-ветка, затем поверх неё накладывается patch из diff MR.
     * Получившийся индекс используется в рамках одного buildContext()
     * и передаётся дальше как локальная переменная.
     */
    public Map<String, List<String>> buildMergedFileIndex(String gitlabUrl, String token,
                                                          String projectId, String targetBranch,
                                                          List<Diff> mrDiffs) {
        log.info("Building merged file index for project={} targetBranch={}", projectId, targetBranch);

        Map<String, List<String>> index = new HashMap<>(buildRawIndex(gitlabUrl, token, projectId, targetBranch));

        for (Diff diff : mrDiffs) {
            String path = diff.getNewPath();
            if (path == null || !path.endsWith(".java")) continue;

            if (diff.getNewFile() || diff.getRenamedFile()) {
                String name = fileName(path);
                index.computeIfAbsent(name, k -> new ArrayList<>()).add(path);
                log.debug("Merged index patch: +{} ({})",
                        path, diff.getNewFile() ? "added" : "renamed");
            }
        }

        log.info("Merged index built: {} unique filenames, project={}", index.size(), projectId);
        return Collections.unmodifiableMap(index);
    }

    /**
     * Найти путь к .java-файлу по qualified name класса в уже построенном индексе.
     *
     * @param fileIndex     мёрженный индекс из {@link #buildMergedFileIndex}
     * @param qualifiedName полное имя класса, например {@code com.example.Foo}
     */
    public Optional<String> findJavaFileByQualifiedName(Map<String, List<String>> fileIndex,
                                                        String qualifiedName) {
        String simpleName = qualifiedName.contains(".")
                ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                : qualifiedName;
        if (simpleName.contains("$")) {
            simpleName = simpleName.substring(0, simpleName.indexOf('$'));
        }
        String fileName = simpleName + ".java";

        List<String> candidates = fileIndex.getOrDefault(fileName, List.of());
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
    // Internal index building
    // -------------------------------------------------------------------------

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
