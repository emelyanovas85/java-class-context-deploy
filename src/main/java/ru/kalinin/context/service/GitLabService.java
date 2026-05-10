package ru.kalinin.context.service;

import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.RepositoryFile;
import org.springframework.stereotype.Service;
import ru.kalinin.context.model.CommitInfo;
import ru.kalinin.context.model.MergeRequestInfo;

import java.util.List;
import java.util.Optional;

/**
 * Взаимодействие с GitLab через gitlab4j-api.
 */
@Slf4j
@Service
public class GitLabService {

    /**
     * Получить метаданные мёрж-реквеста.
     */
    public MergeRequestInfo getMergeRequestInfo(
            String gitlabUrl, String token, String projectId, long mrIid) {

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
     * Возвращает Optional.empty() если файл не найден или не является Java-файлом.
     *
     * @param branch ветка или sha коммита
     * @param filePath путь к файлу в репозитории
     */
    public Optional<String> readFileContent(
            String gitlabUrl, String token, String projectId,
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
                log.debug("File not found in GitLab: {}", filePath);
                return Optional.empty();
            }
            throw new RuntimeException(
                    "Error reading file " + filePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Найти Java-файл в проекте по qualified name класса.
     * Пробует пути src/main/java/, src/test/java/, и корень репозитория.
     *
     * @param qualifiedName полное имя класса, например com.example.Foo
     * @param branch        ветка
     * @return путь к файлу или Optional.empty()
     */
    public Optional<String> findJavaFileByQualifiedName(
            String gitlabUrl, String token, String projectId,
            String qualifiedName, String branch) {

        String topLevelClass = qualifiedName.contains(".")
                ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                : qualifiedName;
        if (topLevelClass.contains("$")) {
            topLevelClass = topLevelClass.substring(0, topLevelClass.indexOf('$'));
        }

        String packagePath = qualifiedName.contains(".")
                ? qualifiedName.substring(0, qualifiedName.lastIndexOf('.')).replace('.', '/')
                : "";

        String candidatePath = packagePath.isEmpty()
                ? topLevelClass + ".java"
                : packagePath + "/" + topLevelClass + ".java";

        for (String prefix : List.of("src/main/java/", "src/test/java/", "")) {
            String fullPath = prefix + candidatePath;
            try (GitLabApi api = new GitLabApi(gitlabUrl, token)) {
                api.getRepositoryFileApi().getFile(projectId, fullPath, branch);
                return Optional.of(fullPath);
            } catch (GitLabApiException e) {
                if (e.getHttpStatus() != 404) {
                    log.warn("Error checking path {}: {}", fullPath, e.getMessage());
                }
            }
        }

        log.debug("Java file not found for class: {}", qualifiedName);
        return Optional.empty();
    }
}
