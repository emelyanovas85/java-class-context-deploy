package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.kalinin.context.dependency.DependencyClassNameExtractor;
import ru.kalinin.context.dependency.DependencyCoordinate;
import ru.kalinin.context.model.SourceLinesRequest;
import ru.kalinin.context.model.SourceLinesResponse;
import ru.kalinin.context.model.SourceLinesResponse.FileResult;
import ru.kalinin.context.model.SourceLinesResponse.Snippet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Получает строки исходного кода из GitLab или из локального {@code sources.jar}
 * по диапазонам, указанным в {@link SourceLinesRequest}.
 *
 * <p>Формат диапазона — тот же, что используется в {@code StructureNode.rows()}:
 * {@code "17"} (одна строка) или {@code "19-22"} (включительно с обеих сторон).
 *
 * <h3>Маршрут для jar-источника</h3>
 * <ol>
 *   <li>{@code source} = {@code "groupId:artifactId:version"} разбирается в {@link DependencyCoordinate}.</li>
 *   <li>jar ищется на диске по пути {@code artifactsDir/<localFileName()>}.</li>
 *   <li>{@link DependencyClassNameExtractor#extractSourceFile} даёт содержимое файла.</li>
 *   <li>{Строки извлекаются так же, как для GitLab-источника.}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceLinesService {

    private final GitLabService gitLabService;
    private final DependencyClassNameExtractor classNameExtractor;

    @Value("${app.artifacts-dir:artifacts}")
    private String artifactsDirPath;

    public SourceLinesResponse fetchLines(SourceLinesRequest request) {
        List<FileResult> results = new ArrayList<>();
        for (SourceLinesRequest.FileLines fileLines : request.files()) {
            results.add(processFile(request, fileLines));
        }
        return new SourceLinesResponse(results);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private FileResult processFile(SourceLinesRequest request,
                                   SourceLinesRequest.FileLines fileLines) {
        Optional<String> content = fileLines.isJarSource()
                ? readFromJar(fileLines)
                : readFromGitLab(request, fileLines);

        if (content.isEmpty()) {
            return FileResult.ofError(fileLines.filePath(),
                    "Content not found for: " + fileLines.filePath()
                    + (fileLines.source() != null ? " [source=" + fileLines.source() + "]" : ""));
        }

        String[] lines = content.get().split("\n", -1);
        List<Snippet> snippets = fileLines.rows().stream()
                .map(rowSpec -> extractSnippet(lines, rowSpec))
                .toList();
        return FileResult.ofSnippets(fileLines.filePath(), snippets);
    }

    /**
     * Читает файл из GitLab.
     */
    private Optional<String> readFromGitLab(SourceLinesRequest request,
                                             SourceLinesRequest.FileLines fileLines) {
        String path = fileLines.filePath();
        log.debug("Reading from GitLab: {}", path);
        try {
            return gitLabService.readRawFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), request.ref(), path);
        } catch (RuntimeException e) {
            log.warn("Failed to read from GitLab {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Читает файл из локального sources.jar.
     *
     * <p>source формат: {@code "groupId:artifactId:version"}.<br>
     * filePath — qualified name класса, например {@code "org.aspectj.weaver.Advice"}.
     */
    private Optional<String> readFromJar(SourceLinesRequest.FileLines fileLines) {
        String source = fileLines.source();
        String qualifiedName = fileLines.filePath();
        log.debug("Reading from jar [{}]: {}", source, qualifiedName);

        String[] parts = source.split(":", 3);
        if (parts.length != 3) {
            log.warn("Invalid source format '{}', expected groupId:artifactId:version", source);
            return Optional.empty();
        }

        DependencyCoordinate coord = new DependencyCoordinate(parts[0], parts[1], parts[2]);
        Path jarPath = Path.of(artifactsDirPath).resolve(coord.localFileName());

        if (!Files.exists(jarPath)) {
            log.warn("sources.jar not found on disk: {}", jarPath);
            return Optional.empty();
        }

        return classNameExtractor.extractSourceFile(jarPath, qualifiedName);
    }

    /**
     * Извлекает строки по спецификации диапазона.
     *
     * @param lines   все строки файла (0-based массив)
     * @param rowSpec диапазон в формате {@code "17"} или {@code "19-22"} (1-based, включительно)
     */
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
