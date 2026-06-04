package service.structure.service;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.RepositoryFile;
import org.gitlab4j.api.models.TreeItem;
import org.springframework.stereotype.Service;
import service.structure.model.CommitInfo;
import service.structure.model.MergeRequestInfo;

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
 *
 * <h2>Кэш индекса</h2>
 * <p>Равный индекс ({@link #buildRawIndex}) кэшируется в {@code fileIndexCache}
 * по ключу {@code "gitlabUrl::projectId::branch"} с TTL
 * {@code expireAfterAccess}. Мёрженный индекс ({@link #buildMergedFileIndex})
 * не кэшируется: его наложение патча специфично для MR и строится
 * быстро поверх кэшированного raw-индекса.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService {

    /**
     * Кэш raw-индексов: ключ = {@code "gitlabUrl::projectId::branch"},
     * значение = {@code Map<simpleName.java, List<fullPath>>}.
     */
    private final Cache<String, Map<String, List<String>>> fileIndexCache;

    private static final List<String> BUILD_FILE_SUFFIXES = List.of(".gradle", ".gradle.kts");
    private static final String POM_XML = "pom.xml";
    private static final String CACHE_KEY_SEPARATOR = "::";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

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

    public Optional<String> readFileContent(String gitlabUrl, String token, String projectId,
                                            String branch, String filePath) {
        if (!filePath.endsWith(".java")) {
            return Optional.empty();
        }
        return readRawFileContent(gitlabUrl, token, projectId, branch, filePath);
    }

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

    public List<String> findBuildFiles(Map<String, List<String>> fileIndex) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : fileIndex.entrySet()) {
            String name = entry.getKey();
            if (isBuildFile(name)) {
                result.addAll(entry.getValue());
            }
        }
        log.debug("findBuildFiles: found {} file(s): {}", result.size(), result);
        return result;
    }

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

    /**
     * Все пути к {@code .java}-файлам в указанном пакете (по merged/raw-индексу).
     * Нужен для package-private top-level типов, живущих в файле с другим именем
     * (например {@code class B {}} в {@code A.java}).
     */
    public List<String> listJavaFilesInPackage(Map<String, List<String>> fileIndex, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return List.of();
        }
        String packagePath = packageName.replace('.', '/') + "/";
        List<String> paths = new ArrayList<>();
        for (List<String> group : fileIndex.values()) {
            for (String path : group) {
                if (!path.endsWith(".java")) continue;
                String afterSourceRoot = pathAfterJavaSourceRoot(path);
                if (afterSourceRoot != null && afterSourceRoot.startsWith(packagePath)) {
                    paths.add(path);
                }
            }
        }
        paths.sort(String::compareTo);
        return paths;
    }

    /**
     * Строит raw-индекс для ветки, используя кэш.
     *
     * <p>При попадании — возвращает из кэша без запроса к GitLab.
     * При промахе — запрашивает полное дерево и сохраняет в кэш.
     */
    public Map<String, List<String>> buildRawIndex(String gitlabUrl, String token,
                                                    String projectId, String branch) {
        String cacheKey = gitlabUrl + CACHE_KEY_SEPARATOR + projectId + CACHE_KEY_SEPARATOR + branch;
        Map<String, List<String>> cached = fileIndexCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("File index cache hit: project={} branch={}", projectId, branch);
            return cached;
        }

        log.info("Building raw file index for project={} branch={}", projectId, branch);
        Map<String, List<String>> index = new HashMap<>();

        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            List<TreeItem> tree = api.getRepositoryApi()
                    .getTree(projectId, null, null, true);
            for (TreeItem item : tree) {
                if (item.getType() == TreeItem.Type.BLOB) {
                    String path = item.getPath();
                    String name = fileName(path);
                    if (isBuildFile(name) || path.endsWith(".java")) {
                        index.computeIfAbsent(name, k -> new ArrayList<>()).add(path);
                    }
                }
            }
        } catch (GitLabApiException e) {
            throw new RuntimeException(
                    "Error building file index for " + projectId + ": " + e.getMessage(), e);
        }

        Map<String, List<String>> unmodifiable = Collections.unmodifiableMap(index);
        fileIndexCache.put(cacheKey, unmodifiable);
        log.info("Raw file index cached: {} unique filenames, project={} branch={}",
                index.size(), projectId, branch);
        return unmodifiable;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private boolean isBuildFile(String name) {
        if (POM_XML.equals(name)) return true;
        for (String suffix : BUILD_FILE_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }
        return false;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** Путь относительно {@code src/.../java/} или {@code null}, если это не Java-исходник. */
    static String pathAfterJavaSourceRoot(String filePath) {
        for (String prefix : List.of("src/main/java/", "src/test/java/", "src/main/kotlin/")) {
            int idx = filePath.indexOf(prefix);
            if (idx >= 0) {
                return filePath.substring(idx + prefix.length());
            }
        }
        return null;
    }
}
