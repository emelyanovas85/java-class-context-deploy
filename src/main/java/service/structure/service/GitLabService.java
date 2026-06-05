package service.structure.service;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.DiffRefs;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.RepositoryFile;
import org.gitlab4j.api.models.TreeItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import service.structure.exception.DiffRefsNotReadyException;
import service.structure.model.CommitInfo;
import service.structure.model.MergeRequestInfo;
import service.structure.model.PinnedRefs;

import java.util.*;

/**
 * Взаимодействие с GitLab через gitlab4j-api.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService {

    private final Cache<String, Map<String, List<String>>> fileIndexCache;

    @Value("${app.review-session.diff-refs-retry-attempts:3}")
    private int diffRefsRetryAttempts;

    @Value("${app.review-session.diff-refs-retry-delay-ms:500}")
    private long diffRefsRetryDelayMs;

    private static final List<String> BUILD_FILE_SUFFIXES = List.of(".gradle", ".gradle.kts");
    private static final String POM_XML = "pom.xml";
    private static final String CACHE_KEY_SEPARATOR = "::";

    /** Снимок MR при создании сессии: метаданные + pin SHA. */
    public record MergeRequestSnapshot(MergeRequestInfo mrInfo, PinnedRefs pinnedRefs) {}

    /**
     * Получает MR, diff и {@code diff_refs} с retry.
     * Используется только при create сессии.
     */
    public MergeRequestSnapshot getMergeRequestSnapshot(String gitlabUrl, String token,
                                                        String projectId, long mrIid) {
        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            MergeRequest mr = fetchMergeRequestWithDiffRefs(api, projectId, mrIid);
            DiffRefs refs = mr.getDiffRefs();
            if (refs == null
                    || refs.getHeadSha() == null
                    || refs.getStartSha() == null
                    || refs.getBaseSha() == null) {
                throw new DiffRefsNotReadyException(mrIid);
            }

            PinnedRefs pinned = new PinnedRefs(
                    refs.getHeadSha(), refs.getStartSha(), refs.getBaseSha());

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

            MergeRequestInfo mrInfo = new MergeRequestInfo(
                    mr.getIid(),
                    mr.getTitle(),
                    mr.getState() != null ? mr.getState().toString() : null,
                    mr.getSourceBranch(),
                    mr.getTargetBranch(),
                    mr.getAuthor() != null ? mr.getAuthor().getUsername() : null,
                    commits,
                    changedFiles,
                    diffs,
                    pinned
            );
            return new MergeRequestSnapshot(mrInfo, pinned);
        } catch (GitLabApiException e) {
            throw new RuntimeException("GitLab API error: " + e.getMessage(), e);
        }
    }

    private MergeRequest fetchMergeRequestWithDiffRefs(GitLabApi api, String projectId, long mrIid)
            throws GitLabApiException {
        GitLabApiException last = null;
        for (int attempt = 1; attempt <= diffRefsRetryAttempts; attempt++) {
            MergeRequest mr = api.getMergeRequestApi().getMergeRequest(projectId, mrIid);
            DiffRefs refs = mr.getDiffRefs();
            if (refs != null
                    && refs.getHeadSha() != null
                    && refs.getStartSha() != null
                    && refs.getBaseSha() != null) {
                return mr;
            }
            if (attempt < diffRefsRetryAttempts) {
                log.debug("diff_refs not ready for MR !{}, retry {}/{}",
                        mrIid, attempt, diffRefsRetryAttempts);
                try {
                    Thread.sleep(diffRefsRetryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for diff_refs", e);
                }
            } else {
                last = new GitLabApiException("diff_refs not ready");
            }
        }
        throw last != null ? last : new GitLabApiException("diff_refs not ready");
    }

    public Optional<String> readFileContent(String gitlabUrl, String token, String projectId,
                                            String ref, String filePath) {
        if (!filePath.endsWith(".java")) {
            return Optional.empty();
        }
        return readRawFileContent(gitlabUrl, token, projectId, ref, filePath);
    }

    public Optional<String> readRawFileContent(String gitlabUrl, String token, String projectId,
                                               String ref, String filePath) {
        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            RepositoryFile file = api.getRepositoryFileApi()
                    .getFile(projectId, filePath, ref);
            String content = new String(
                    Base64.getDecoder().decode(file.getContent()));
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
                                                          String projectId, String targetRef,
                                                          List<Diff> mrDiffs) {
        log.info("Building merged file index for project={} targetRef={}", projectId, targetRef);

        Map<String, List<String>> index = new HashMap<>(buildRawIndex(gitlabUrl, token, projectId, targetRef));

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
        List<String> all = findAllJavaPathsByName(fileIndex, qualifiedName, true);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Все пути в индексе, соответствующие simple или qualified имени.
     *
     * @param qualifiedMode true — фильтр по package suffix; false — все пути с данным simple name
     */
    public List<String> findAllJavaPathsByName(Map<String, List<String>> fileIndex,
                                               String name,
                                               boolean qualifiedMode) {
        String normalized = normalizeName(name);
        boolean isQualified = normalized.contains(".");
        String simpleName = isQualified
                ? normalized.substring(normalized.lastIndexOf('.') + 1)
                : normalized;
        if (simpleName.contains("$")) {
            simpleName = simpleName.substring(0, simpleName.indexOf('$'));
        }
        String fileKey = simpleName + ".java";
        List<String> candidates = new ArrayList<>(fileIndex.getOrDefault(fileKey, List.of()));
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!qualifiedMode || !isQualified) {
            candidates.sort(String::compareTo);
            return List.copyOf(candidates);
        }
        String packageSuffix = normalized.replace('.', '/') + ".java";
        List<String> filtered = candidates.stream()
                .filter(path -> path.endsWith(packageSuffix))
                .sorted()
                .toList();
        return List.copyOf(filtered);
    }

    /** Убирает пробелы и суффикс {@code .java} из имени класса/файла. */
    public static String normalizeName(String name) {
        String n = name.trim();
        if (n.endsWith(".java")) {
            n = n.substring(0, n.length() - 5);
        }
        return n;
    }

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

    public Map<String, List<String>> buildRawIndex(String gitlabUrl, String token,
                                                    String projectId, String ref) {
        String cacheKey = gitlabUrl + CACHE_KEY_SEPARATOR + projectId + CACHE_KEY_SEPARATOR + ref;
        Map<String, List<String>> cached = fileIndexCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("File index cache hit: project={} ref={}", projectId, ref);
            return cached;
        }

        log.info("Building raw file index for project={} ref={}", projectId, ref);
        Map<String, List<String>> index = new HashMap<>();

        try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
            List<TreeItem> tree = api.getRepositoryApi()
                    .getTree(projectId, null, ref, true);
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
        log.info("Raw file index cached: {} unique filenames, project={} ref={}",
                index.size(), projectId, ref);
        return unmodifiable;
    }

    /** Строит qualified name из пути {@code src/.../java/.../Foo.java}. */
    public static String qualifiedNameFromRepoPath(String filePath) {
        String relative = pathAfterJavaSourceRoot(filePath);
        if (relative == null || !relative.endsWith(".java")) {
            return null;
        }
        String withoutExt = relative.substring(0, relative.length() - 5);
        return withoutExt.replace('/', '.');
    }

    /** Метка модуля для repo-файла: {@code src/main} или {@code src/test}. */
    public static String repoModuleLabel(String filePath) {
        if (filePath.startsWith("src/test/")) return "src/test";
        if (filePath.startsWith("src/main/")) return "src/main";
        return "src/main";
    }

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
