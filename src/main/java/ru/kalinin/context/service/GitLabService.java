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
 * <p>Для поиска файлов по qualified name используется файловый индекс,
 * построенный одним запросом {@code repository/tree?recursive=true}
 * при первом обращении к данному проекту+ветке.
 * Индекс кэшируется в памяти по ключу projectId+branch.
 */
@Slf4j
@Service
public class GitLabService {

    /**
     * Кэш: (projectId, branch) → индекс .java-файлов.
     * Структура индекса: simpleName.java → List<fullPath>
     * (list нужен на случай одноимённых файлов в разных пакетах)
     */
    private final Map<String, Map<String, List<String>>> fileIndexCache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Получить метаданные мёрж-реквеста
     */
    public MergeRequestInfo getMergeRequestInfo(String gitlabUrl, String token, String projectId, long mrIid) {

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

            List<String> changedFiles = api.getMergeRequestApi()
                    .getMergeRequestChanges(projectId, mrIid)
                    .getChanges()
                    .stream()
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
                    changedFiles
            );
        } catch (GitLabApiException e) {
            throw new RuntimeException("GitLab API error: " + e.getMessage(), e);
        }
    }

    /**
     * Прочитать содержимое файла из репозитория GitLab.
     * Возвращает {@code Optional.empty()} если файл не найден или не java
     */
    public Optional<String> readFileContent(String gitlabUrl, String token, String projectId,
                                            String branch, String filePath) {

        if (!filePath.endsWith(".java")) {
            return Optional.empty();
        }

        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            RepositoryFile file = api.getRepositoryFileApi()
                    .getFile(projectId, filePath, branch);
            String content = new String(
                    java.util.Base64.getDecoder().decode(file.getContent()));
            return Optional.of(content);
        } catch (GitLabApiException e) {
            if (e.getHttpStatus() == 404) {
//                log.debug("File not found in GitLab: {}", filePath);
                return Optional.empty();
            }
            throw new RuntimeException(
                    "Error reading file " + filePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Найти путь к .java-файлу по qualified name класса.
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Получить (/ построить) индекс .java-файлов для данной ветки.</li>
     *   <li>Извлечь simple name: {@code com.example.Foo} → {@code Foo.java}.</li>
     *   <li>Найти путь в индексе, уточнить через package-суффикс если несколько падений.</li>
     *   <li>Если не найдено — {@code Optional.empty()} (фоллбэк на simple name в вызывающем коде).</li>
     * </ol>
     *
     * @param qualifiedName полное имя класса, например {@code com.example.Foo}
     * @param branch        ветка
     */
    public Optional<String> findJavaFileByQualifiedName(String gitlabUrl, String token, String projectId,
                                                        String qualifiedName, String branch) {

        Map<String, List<String>> index = getOrBuildIndex(gitlabUrl, token, projectId, branch);

        // simple name файла: com.example.Foo → Foo.java
        String simpleName = qualifiedName.contains(".")
                ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                : qualifiedName;
        // внутренние классы: Outer$Inner → ищем Outer.java
        if (simpleName.contains("$")) {
            simpleName = simpleName.substring(0, simpleName.indexOf('$'));
        }
        String fileName = simpleName + ".java";

        List<String> candidates = index.getOrDefault(fileName, List.of());
        if (candidates.isEmpty()) {
            log.debug("No file found in index for class: {}", qualifiedName);
            return Optional.empty();
        }

        // если файл один — возвращаем сразу
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }

        // несколько кандидатов: уточняем по package-суффиксу пути
        // com.example.Foo → ищем файл, чей путь заканчивается на com/example/Foo.java
        String packageSuffix = qualifiedName.replace('.', '/') + ".java";
        Optional<String> exact = candidates.stream()
                .filter(path -> path.endsWith(packageSuffix))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }

        // если пакет не уточнил — берём первый (src/main/java предпочтительнее тестов)
        Optional<String> mainFirst = candidates.stream()
                .filter(p -> p.contains("src/main/java"))
                .findFirst();
        return mainFirst.isPresent() ? mainFirst : Optional.of(candidates.get(0));
    }

    // -------------------------------------------------------------------------
    // Index
    // -------------------------------------------------------------------------

    /**
     * Возвращает индекс из кэша; если отсутствует — строит одним вызовом
     * {@code GET /projects/:id/repository/tree?recursive=true&per_page=100}.
     *
     * <p>Ключ кэша: {@code projectId + "#" + branch}
     */
    private Map<String, List<String>> getOrBuildIndex(String gitlabUrl, String token, String projectId, String branch) {
        String cacheKey = projectId + "#" + branch;
        return fileIndexCache.computeIfAbsent(cacheKey,
                k -> buildFileIndex(gitlabUrl, token, projectId, branch));
    }

    /**
     * Строит индекс всех файлов в репозитории.
     *
     * <p>Использует {@code getTree()} gitlab4j с {@code recursive=true}.
     * gitlab4j сам обрабатывает пагинацию, поэтому просто забираем все страницы.
     *
     * @return карта filename → [полные пути]
     */
    private Map<String, List<String>> buildFileIndex(String gitlabUrl, String token, String projectId, String branch) {
        log.info("Building file index for project={} branch={}", projectId, branch);
        Map<String, List<String>> index = new HashMap<>();

        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            // getTree с recursive=true возвращает Pager;
            // перебираем все страницы
            var pager = api.getRepositoryApi()
                    .getTree(projectId, null, branch, true, 100);

            while (pager.hasNext()) {
                for (TreeItem item : pager.next()) {
                    if (item.getType() == TreeItem.Type.BLOB) {// && item.getName().endsWith(".java")) {
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

        log.info("File index built: {} unique filenames, project={} branch={}",
                index.size(), projectId, branch);
        return index;
    }
}
