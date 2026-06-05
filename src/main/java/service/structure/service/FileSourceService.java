package service.structure.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import service.structure.dependency.DependencyClassNameExtractor;
import service.structure.model.FileSourceResponse;
import service.structure.model.FileSourceResponse.FileMatch;
import service.structure.session.ReviewSession;
import service.structure.session.ReviewSessionService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Поиск исходников по simple/qualified имени в merged index и dependencySources сессии.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSourceService {

    private final GitLabService gitLabService;
    private final DependencyClassNameExtractor classNameExtractor;
    private final ReviewSessionService reviewSessionService;

    /** Возвращает все совпадения (repo + jar), без выбора одного кандидата. */
    public FileSourceResponse resolve(ReviewSession session, String name) {
        String normalized = GitLabService.normalizeName(name);
        boolean qualified = normalized.contains(".");

        List<FileMatch> repoMatches = resolveRepo(session, normalized, qualified);
        List<FileMatch> depMatches = resolveDependencies(session, normalized, qualified);

        List<FileMatch> all = new ArrayList<>(repoMatches.size() + depMatches.size());
        all.addAll(repoMatches);
        all.addAll(depMatches);
        return new FileSourceResponse(name, all);
    }

    private List<FileMatch> resolveRepo(ReviewSession session, String normalized, boolean qualified) {
        List<String> paths = gitLabService.findAllJavaPathsByName(
                session.mergedFileIndex(), normalized, qualified);
        List<FileMatch> matches = new ArrayList<>();
        for (String path : paths) {
            Optional<String> content = gitLabService.readRawFileContent(
                    session.gitlabUrl(), session.token(), session.projectId(),
                    session.sourceSha(), path);
            if (content.isEmpty()) continue;
            String qName = GitLabService.qualifiedNameFromRepoPath(path);
            if (qName == null) {
                qName = normalized;
            }
            matches.add(new FileMatch(
                    "repo",
                    path,
                    qName,
                    GitLabService.repoModuleLabel(path),
                    content.get()
            ));
        }
        return matches;
    }

    private List<FileMatch> resolveDependencies(ReviewSession session,
                                                String normalized,
                                                boolean qualified) {
        Map<String, Path> dependencySources =
                reviewSessionService.getOrBuildDependencySources(session);
        Set<String> keys = new LinkedHashSet<>();

        if (qualified) {
            collectQualifiedDependencyKeys(keys, dependencySources, normalized);
        } else {
            for (String qName : dependencySources.keySet()) {
                if (matchesSimpleName(qName, normalized)) {
                    keys.add(qName);
                }
            }
        }

        List<FileMatch> matches = new ArrayList<>();
        for (String qName : keys) {
            Path jarPath = dependencySources.get(qName);
            if (jarPath == null) continue;
            Optional<String> content = classNameExtractor.extractSourceFile(jarPath, qName);
            if (content.isEmpty()) continue;
            matches.add(new FileMatch(
                    "dependency",
                    qName.replace('.', '/') + ".java",
                    qName,
                    jarModuleLabel(jarPath),
                    content.get()
            ));
        }
        matches.sort((a, b) -> a.path().compareTo(b.path()));
        return matches;
    }

    private void collectQualifiedDependencyKeys(Set<String> keys,
                                                Map<String, Path> dependencySources,
                                                String normalized) {
        String candidate = normalized;
        while (!candidate.isEmpty()) {
            if (dependencySources.containsKey(candidate)) {
                keys.add(candidate);
            }
            int lastDot = candidate.lastIndexOf('.');
            if (lastDot < 0) break;
            candidate = candidate.substring(0, lastDot);
        }
    }

    private static boolean matchesSimpleName(String qName, String simpleName) {
        int lastDot = qName.lastIndexOf('.');
        String simple = lastDot < 0 ? qName : qName.substring(lastDot + 1);
        if (simple.contains("$")) {
            simple = simple.substring(0, simple.indexOf('$'));
        }
        return simple.equals(simpleName);
    }

    private static String jarModuleLabel(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        if (!fileName.endsWith("-sources.jar")) {
            return fileName;
        }
        String base = fileName.substring(0, fileName.length() - "-sources.jar".length());
        int lastDash = base.lastIndexOf('-');
        if (lastDash <= 0) return base;
        String version = base.substring(lastDash + 1);
        String artifact = base.substring(0, lastDash);
        int artifactDash = artifact.lastIndexOf('-');
        if (artifactDash <= 0) return artifact + ":" + version;
        String groupId = artifact.substring(0, artifactDash).replace('-', '.');
        String artifactId = artifact.substring(artifactDash + 1);
        return groupId + ":" + artifactId + ":" + version;
    }
}
