package service.structure.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import service.structure.dependency.DependencyClassNameExtractor;
import service.structure.dependency.DependencyCoordinate;
import service.structure.model.ClassLines;
import service.structure.model.GitLabLinesSessionRequest;
import service.structure.model.JarLinesRequest;
import service.structure.session.ReviewSession;
import service.structure.model.SourceLinesResponse;
import service.structure.model.SourceLinesResponse.FileResult;
import service.structure.parser.JavaStructureParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Получает строки исходного кода из GitLab или из локального {@code sources.jar}.
 *
 * <p>Формат диапазона — тот же, что в {@code StructureNode.rows()}:
 * {@code "17"} (одна строка) или {@code "19-22"} (включительно).
 * Строки всех диапазонов объединяются, дедуплицируются по номеру строки
 * и отдаются одним списком с префиксом номера строки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceLinesService {

    private final GitLabService gitLabService;
    private final DependencyClassNameExtractor classNameExtractor;
    private final JavaStructureParser structureParser;

    @Value("${app.artifacts-dir:artifacts}")
    private String artifactsDirPath;

    // ── GitLab ────────────────────────────────────────────────────────────

    /**
     * Строки из GitLab по сессии: ref = pinned {@code sourceSha}, index = merged index.
     */
    public SourceLinesResponse fetchFromGitLab(ReviewSession session, GitLabLinesSessionRequest request) {
        Map<String, List<String>> index = session.mergedFileIndex();
        List<FileResult> results = request.classes().stream()
                .map(c -> processGitLabClass(session, index, c))
                .toList();
        return new SourceLinesResponse(results);
    }

    private FileResult processGitLabClass(ReviewSession session,
                                          Map<String, List<String>> index,
                                          ClassLines classLines) {
        String qName = classLines.qualifiedName();
        String pathPrefix = classLines.sourcePathPrefix();

        Optional<String> filePath = resolveGitLabFilePath(session, index, qName, pathPrefix);
        if (filePath.isEmpty()) {
            log.warn("Class not found in index: {} (source={})", qName, classLines.source());
            return FileResult.ofError(qName,
                    "File not found in repository for class: " + qName
                    + (pathPrefix != null ? " under " + pathPrefix : ""));
        }

        log.debug("Resolved {} -> {}", qName, filePath.get());

        Optional<String> content;
        try {
            content = gitLabService.readRawFileContent(
                    session.gitlabUrl(), session.token(),
                    session.projectId(), session.sourceSha(), filePath.get());
        } catch (RuntimeException e) {
            log.warn("Failed to read {}: {}", filePath.get(), e.getMessage());
            return FileResult.ofError(qName, e.getMessage());
        }

        if (content.isEmpty()) {
            return FileResult.ofError(qName, "File not found: " + filePath.get());
        }

        return toFileResult(qName, content.get(), classLines.rows());
    }

    private Optional<String> resolveGitLabFilePath(ReviewSession session,
                                                   Map<String, List<String>> index,
                                                   String qualifiedName,
                                                   String pathPrefix) {
        Optional<String> direct = findWithSourceHint(index, qualifiedName, pathPrefix);
        if (direct.isPresent()) return direct;

        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) return Optional.empty();
        String packageName = qualifiedName.substring(0, lastDot);
        String simpleName = qualifiedName.substring(lastDot + 1);

        for (String candidatePath : gitLabService.listJavaFilesInPackage(index, packageName)) {
            try {
                Optional<String> content = gitLabService.readRawFileContent(
                        session.gitlabUrl(), session.token(),
                        session.projectId(), session.sourceSha(), candidatePath);
                if (content.isPresent()
                        && structureParser.containsTopLevelType(content.get(), simpleName)) {
                    log.debug("Resolved {} via package scan in {}", qualifiedName, candidatePath);
                    return Optional.of(candidatePath);
                }
            } catch (RuntimeException e) {
                log.debug("Skip {} while resolving {}: {}", candidatePath, qualifiedName, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<String> findWithSourceHint(Map<String, List<String>> index,
                                                String qualifiedName,
                                                String pathPrefix) {
        String simpleName = qualifiedName.contains(".")
                ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                : qualifiedName;
        if (simpleName.contains("$")) {
            simpleName = simpleName.substring(0, simpleName.indexOf('$'));
        }

        List<String> candidates = index.getOrDefault(simpleName + ".java", List.of());
        if (candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0));

        String packageSuffix = qualifiedName.replace('.', '/') + ".java";

        if (pathPrefix != null) {
            Optional<String> hit = candidates.stream()
                    .filter(p -> p.startsWith(pathPrefix) && p.endsWith(packageSuffix))
                    .findFirst();
            if (hit.isPresent()) return hit;
        }

        Optional<String> byPackage = candidates.stream()
                .filter(p -> p.endsWith(packageSuffix))
                .findFirst();
        if (byPackage.isPresent()) return byPackage;

        return Optional.of(candidates.get(0));
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

    // ── Общая логика ──────────────────────────────────────────────────────

    /**
     * Собирает строки всех диапазонов в один список.
     * Номера строк дедуплицируются через {@link Set} — порядок появления сохраняется.
     */
    private FileResult toFileResult(String label, String content, List<String> rows) {
        String[] lines = content.split("\n", -1);

        Set<Integer> lineNumbers = new HashSet<>();
        for (String rowSpec : rows) {
            collectLineNumbers(lineNumbers, lines.length, rowSpec);
        }

        List<String> snippets = new ArrayList<>(lineNumbers.size());
        lineNumbers.stream()
                .sorted()
                .forEach(n -> snippets.add(n + ": " + lines[n - 1]));
        return FileResult.ofSnippets(label, snippets);
    }

    /**
     * Добавляет номера строк диапазона в переданный set (1-based, включительно).
     * Номера за границами файла молча отбрасываются.
     *
     * @param dest      приёмник номеров строк
     * @param fileLines количество строк в файле
     * @param rowSpec   диапазон {@code "17"} или {@code "19-22"}
     */
    static void collectLineNumbers(Set<Integer> dest, int fileLines, String rowSpec) {
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
            return;
        }

        int clampedFrom = Math.max(1, from);
        int clampedTo   = Math.min(fileLines, to);

        if (clampedFrom > clampedTo) {
            log.warn("Row spec '{}' out of range (file has {} lines)", rowSpec, fileLines);
            return;
        }

        for (int i = clampedFrom; i <= clampedTo; i++) {
            dest.add(i);
        }
    }
}
