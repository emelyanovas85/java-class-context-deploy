package ru.kalinin.context.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.kalinin.context.model.SourceLinesRequest;
import ru.kalinin.context.model.SourceLinesResponse;
import ru.kalinin.context.model.SourceLinesResponse.FileResult;
import ru.kalinin.context.model.SourceLinesResponse.Snippet;

import java.util.ArrayList;
import java.util.List;

/**
 * Получает строки исходного кода из GitLab по диапазонам,
 * указанным в {@link SourceLinesRequest}.
 *
 * <p>Формат диапазона — тот же, что используется в {@code StructureNode.rows()}:
 * {@code "17"} (одна строка) или {@code "19-22"} (включительно с обеих сторон).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceLinesService {

    private final GitLabService gitLabService;

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
        String path = fileLines.filePath();
        log.debug("Fetching lines for {}, rows={}", path, fileLines.rows());

        String content;
        try {
            var opt = gitLabService.readRawFileContent(
                    request.gitlabUrl(), request.token(),
                    request.projectId(), request.ref(), path);
            if (opt.isEmpty()) {
                log.warn("File not found: {}", path);
                return FileResult.ofError(path, "File not found: " + path);
            }
            content = opt.get();
        } catch (RuntimeException e) {
            log.warn("Failed to read file {}: {}", path, e.getMessage());
            return FileResult.ofError(path, e.getMessage());
        }

        String[] lines = content.split("\n", -1);
        List<Snippet> snippets = new ArrayList<>();

        for (String rowSpec : fileLines.rows()) {
            snippets.add(extractSnippet(lines, rowSpec));
        }

        return FileResult.ofSnippets(path, snippets);
    }

    /**
     * Извлекает строки по спецификации диапазона.
     *
     * @param lines   все строки файла (0-based массив)
     * @param rowSpec диапазон в формате {@code "17"} или {@code "19-22"} (1-based, включительно)
     */
    private Snippet extractSnippet(String[] lines, String rowSpec) {
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

        // Привести к допустимым границам
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
