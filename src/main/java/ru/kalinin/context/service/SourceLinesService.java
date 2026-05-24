package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.kalinin.context.dependency.DependencyClassNameExtractor;
import ru.kalinin.context.dependency.DependencyCoordinate;
import ru.kalinin.context.model.GitLabLinesRequest;
import ru.kalinin.context.model.JarLinesRequest;
import ru.kalinin.context.model.SourceLinesResponse;
import ru.kalinin.context.model.SourceLinesResponse.FileResult;
import ru.kalinin.context.model.SourceLinesResponse.Snippet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Получает строки исходного кода из GitLab или из локального {@code sources.jar}.
 *
 * <p>Формат диапазона — тот же, что в {@code StructureNode.rows()}:
 * {@code "17"} (одна строка) или {@code "19-22"} (включительно с обеих сторон).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceLinesService {

    private final GitLabService gitLabService;
    private final DependencyClassNameExtractor classNameExtractor;

    @Value("${app.artifacts-dir:artifacts}")
    private String artifactsDirPath;

    // ── GitLab ────────────────────────────────────────────────────────────

    /**
     * Читает строки из GitLab.
     *
     * <p>Файл резолвится по qualified name через файловый индекс
     * (TTL-кэш 15 минут: первый вызов медленный, повторные — мгновенные).
     */
    public SourceLinesResponse fetchFromGitLab(GitLabLinesRequest request) {
        // Индекс берётся из кэша или строится один раз для всех классов в запросе
        Map<String, List<String>> index = gitLabService.buildRawIndex(
                request.gitlabUrl(), request.token(), request.projectId(), request.ref());

        List<FileResult> results = request.classes().stream()
                .map(c -> processGitLabClass(request, index, c))
                .toList();
        return new SourceLinesResponse(results);
    }

    private FileResult processGitLabClass(GitLabLinesRequest request,
                                          Map<String, List<String>> index,
                                          GitLabLinesRequest.ClassLines classLines) {
        String qName = classLines.qualifiedName();

        Optional<String> filePath = gitLabService.findJavaFileByQualifiedName(index, qName);
        if (filePath.isEmpty()) {
            log.warn("Class not found in index: {}", qName);
            return FileResult.ofError(qName, "File not found in repository for class: " + qName);
        }

        log.debug("Resolved {} -> {}", qName, filePath.get());

        Optional<String> content;
        try {
            content = gitLabService.readRawFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), request.ref(), filePath.get());
        } catch (RuntimeException e) {
            log.warn("Failed to read {}: {}", filePath.get(), e.getMessage());
            return FileResult.ofError(qName, e.getMessage());
        }

        if (content.isEmpty()) {
            return FileResult.ofError(qName, "File not found: " + filePath.get());
        }

        return toFileResult(qName, content.get(), classLines.rows());
    }

    // ── Jar ───────────────────────────────────────────────────────────────

    public SourceLinesResponse fetchFromJar(JarLinesRequest request) {
        String[] parts = request.source().split(":", 3);
        DependencyCoordinate coord = new DependencyCoordinate(parts[0], parts[1], parts[2]);
        Path jarPath = Path.of(artifactsDirPath).resolve(coord.localFileName());

        if (!Files.exists(jarPath)) {
            log.warn("sources.jar not found: {}", jarPath);
            List<FileResult> errors = request.classes().stream()
                    .map(c -> FileResult.ofError(c.qualifiedName(),
                            "sources.jar not found for " + request.source()))
                    .toList();
            return new SourceLinesResponse(errors);
        }

        List<FileResult> results = request.classes().stream()
                .map(c -> processJarClass(jarPath, c))
                .toList();
        return new SourceLinesResponse(results);
    }

    private FileResult processJarClass(Path jarPath, JarLinesRequest.ClassLines classLines) {
        String qName = classLines.qualifiedName();
        log.debug("Reading from jar [{}]: {}", jarPath.getFileName(), qName);

        Optional<String> content = classNameExtractor.extractSourceFile(jarPath, qName);
        if (content.isEmpty()) {
            log.warn("Class not found in jar: {}", qName);
            return FileResult.ofError(qName, "Class not found in sources.jar: " + qName);
        }

        return toFileResult(qName, content.get(), classLines.rows());
    }

    // ── Общая логика извлечения строк ───────────────────────────────────────

    private FileResult toFileResult(String label, String content, List<String> rows) {
        String[] lines = content.split("\n", -1);
        List<Snippet> snippets = rows.stream()
                .map(rowSpec -> extractSnippet(lines, rowSpec))
                .toList();
        return FileResult.ofSnippets(label, snippets);
    }

    static Snippet extractSnippet(String[] lines, String rowSpec) {
        int dash = rowSpec.indexOf('-');
        int from, to;
        try {
            if (dash < 0) {
                from = to = Integer.parseInt(rowSpec.trim());
            } else {
                from = Integer.parseInt(rowSpec.substring(0, dash).trim());
                to   = Integer.parseInt(rowSpec.substring(dash + 1).trim());
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid row spec '{}': {}", rowSpec, e.getMessage());
            return new Snippet(rowSpec, "// invalid row spec: " + rowSpec);
        }

        int clampedFrom = Math.max(1, from);
        int clampedTo   = Math.min(lines.length, to);

        if (clampedFrom > clampedTo) {
            return new Snippet(rowSpec, "// lines " + rowSpec + " out of range (file has "
                    + lines.length + " lines)");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = clampedFrom; i <= clampedTo; i++) {
            sb.append(i).append(": ").append(lines[i - 1]);
            if (i < clampedTo) sb.append('\n');
        }
        return new Snippet(rowSpec, sb.toString());
    }
}
