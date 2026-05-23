package ru.kalinin.context.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Скачивает {@code *-sources.jar}, {@code *.module} и {@code maven-metadata.xml}
 * из Artifactory и сохраняет их на диск.
 *
 * <h3>Кэширование на диске</h3>
 * <p>Каждый скачанный файл сохраняется в {@code artifactsDir} (по умолчанию {@code /artifacts}).
 * Двойное подчёркивание ({@code __}) используется как разделитель для избежания
 * неоднозначности при дефисе в groupId/artifactId/version.
 * При повторном запросе файл читается с диска без сетевого вызова.
 *
 * <h3>Динамические версии</h3>
 * <p>Если версия в {@code .module}-файле содержит {@code +} или
 * записана в формате Maven range ({@code [x,)}, {@code (,x]}, ...),
 * выполняется резолвинг через {@code maven-metadata.xml}:
 * <ol>
 *   <li>Скачать / взять с диска {@code maven-metadata.xml} для данного
 *       {@code groupId:artifactId}.</li>
 *   <li>Извлечь {@code <release>} → если нет, {@code <latest>} → если нет,
 *       последнюю версию из {@code <versions>}.</li>
 *   <li>Для range-записей (например {@code [2.1.0,)}) из
 *       списка версий выбирается наибольшая версия,
 *       удовлетворяющая ограничению (lower bound).</li>
 * </ol>
 * {@code maven-metadata.xml} кэшируется на диск с префиксом {@code metadata__}
 * для отличия от jar-файлов.
 */
@Slf4j
@Component
public class ArtifactorySourcesLoader {

    private static final Pattern ARTIFACTORY_URL_PATTERN = Pattern.compile(
            "(https?://[^\\s'\"\u2018\u2019\u201c\u201d,)]+artifactory[^\\s'\"\u2018\u2019\u201c\u201d,)]*)",
            Pattern.CASE_INSENSITIVE);

    /** Директория для хранения скачанных файлов (jar, .module, metadata). */
    private final Path artifactsDir;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ArtifactorySourcesLoader(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.artifacts-dir:artifacts}") String artifactsDirPath) {
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.artifactsDir = Path.of(artifactsDirPath);
        try {
            Files.createDirectories(artifactsDir);
            log.info("Artifacts directory: {}", artifactsDir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Cannot create artifacts directory {}: {}", artifactsDir, e.getMessage());
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public List<String> detectArtifactoryUrls(List<String> gradleFileContents) {
        List<String> urls = new ArrayList<>();
        for (String content : gradleFileContents) {
            Matcher m = ARTIFACTORY_URL_PATTERN.matcher(content);
            while (m.find()) {
                String url = normalizeUrl(m.group(1));
                if (!urls.contains(url)) {
                    urls.add(url);
                    log.debug("Found Artifactory repo URL: {}", url);
                }
            }
        }
        if (urls.isEmpty()) {
            log.warn("No Artifactory URLs found in Gradle files");
        } else {
            log.info("Detected {} Artifactory repo URL(s): {}", urls.size(), urls);
        }
        return List.copyOf(urls);
    }

    public Optional<Path> resolveSourcesJar(List<String> artifactoryRepoUrls,
                                            DependencyCoordinate dep) {
        if (!dep.hasVersion()) {
            log.debug("Skipping BOM-managed dependency without version: {}", dep);
            return Optional.empty();
        }
        Path localPath = artifactsDir.resolve(dep.localFileName());
        if (Files.exists(localPath)) {
            log.debug("sources.jar cache hit (disk): {}", localPath);
            return Optional.of(localPath);
        }
        String relativePath = dep.mavenRelativePath() + dep.sourcesJarName();
        return downloadToFile(artifactoryRepoUrls, relativePath, localPath,
                "sources.jar", dep.toString());
    }

    public Optional<Path> resolveModuleFile(List<String> artifactoryRepoUrls,
                                            DependencyCoordinate dep) {
        if (!dep.hasVersion()) {
            log.debug("Skipping BOM-managed dependency without version (module): {}", dep);
            return Optional.empty();
        }
        Path localPath = artifactsDir.resolve(dep.localModuleFileName());
        if (Files.exists(localPath)) {
            log.debug(".module cache hit (disk): {}", localPath);
            return Optional.of(localPath);
        }
        String relativePath = dep.mavenRelativePath() + dep.moduleFileName();
        return downloadToFile(artifactoryRepoUrls, relativePath, localPath,
                ".module", dep.toString());
    }

    /**
     * Парсит {@code .module}-файл и возвращает api-зависимости из варианта {@code apiElements}.
     *
     * <p>Если версия зависимости является динамической (contains {@code +} or Maven range),
     * резолвит её через {@code maven-metadata.xml}.
     *
     * @param moduleFilePath      путь к скачанному {@code .module}-файлу
     * @param parentDep           родительская зависимость (для логов)
     * @param artifactoryRepoUrls список repo URL для резолвинга динамических версий
     * @return список api-зависимостей с резолвенными версиями, может быть пустым
     */
    public List<DependencyCoordinate> parseApiDependencies(Path moduleFilePath,
                                                           DependencyCoordinate parentDep,
                                                           List<String> artifactoryRepoUrls) {
        List<DependencyCoordinate> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(moduleFilePath.toFile());
            JsonNode variants = root.path("variants");
            for (JsonNode variant : variants) {
                if (!"apiElements".equals(variant.path("name").asText())) continue;

                for (JsonNode dep : variant.path("dependencies")) {
                    String group  = dep.path("group").asText(null);
                    String module = dep.path("module").asText(null);
                    if (group == null || module == null) continue;

                    String version = extractVersionString(dep.path("version"));
                    version = resolveVersionIfDynamic(version, group, module, artifactoryRepoUrls);

                    result.add(new DependencyCoordinate(group, module, version));
                    log.debug("Found api dep in .module of {}: {}:{}:{}",
                            parentDep, group, module, version);
                }
                break; // apiElements нашли, дальше не идём
            }
        } catch (IOException e) {
            log.warn("Failed to parse .module file {}: {}", moduleFilePath, e.getMessage());
        }
        log.debug("Parsed {} api deps from .module of {}", result.size(), parentDep);
        return result;
    }

    /**
     * @deprecated Используйте {@link #parseApiDependencies(Path, DependencyCoordinate, List)}.
     * Оставлен для обратной совместимости в тестах.
     */
    @Deprecated
    public List<DependencyCoordinate> parseApiDependencies(Path moduleFilePath,
                                                           DependencyCoordinate parentDep) {
        return parseApiDependencies(moduleFilePath, parentDep, List.of());
    }

    // ── Динамические версии ─────────────────────────────────────────────

    /**
     * Если версия динамическая — резолвит её через {@code maven-metadata.xml}.
     * Если статическая — возвращает как есть.
     */
    private String resolveVersionIfDynamic(String version, String group, String module,
                                           List<String> repoUrls) {
        if (version == null || !isDynamicVersion(version)) return version;

        log.debug("Dynamic version '{}' for {}:{} — resolving via maven-metadata.xml",
                version, group, module);

        Optional<String> resolved = fetchMavenMetadata(group, module, repoUrls)
                .flatMap(xml -> resolveFromMetadata(xml, version, group, module));

        if (resolved.isPresent()) {
            log.info("Resolved dynamic version '{}' for {}:{} → '{}'",
                    version, group, module, resolved.get());
            return resolved.get();
        }

        log.warn("Could not resolve dynamic version '{}' for {}:{} — keeping as-is",
                version, group, module);
        return version;
    }

    /**
     * Возвращает {@code true} если версия динамическая:
     * содержит {@code +} или есть Maven range-запись (начинается на {@code [} или {@code (}).
     */
    static boolean isDynamicVersion(String version) {
        if (version == null) return false;
        String trimmed = version.trim();
        return trimmed.contains("+")
                || trimmed.startsWith("[")
                || trimmed.startsWith("(");
    }

    /**
     * Скачивает / возвращает с диска содержимое {@code maven-metadata.xml}.
     * Кэш-файл: {@code metadata__<group>__<module>.xml}
     */
    private Optional<String> fetchMavenMetadata(String group, String module,
                                                 List<String> repoUrls) {
        String safeName = group.replace('.', '_') + "__" + module;
        Path localPath = artifactsDir.resolve("metadata__" + safeName + ".xml");

        if (Files.exists(localPath)) {
            log.debug("maven-metadata.xml cache hit: {}", localPath);
            try {
                return Optional.of(Files.readString(localPath));
            } catch (IOException e) {
                log.warn("Failed to read cached metadata {}: {}", localPath, e.getMessage());
            }
        }

        // Построить путь: group/module/maven-metadata.xml
        String relPath = group.replace('.', '/') + '/' + module + "/maven-metadata.xml";

        for (String repoUrl : repoUrls) {
            String base = repoUrl.endsWith("/")
                    ? repoUrl.substring(0, repoUrl.length() - 1) : repoUrl;
            String url  = base + '/' + relPath;
            log.debug("Trying maven-metadata.xml: {}", url);
            try {
                String xml = restClient.get().uri(url).retrieve().body(String.class);
                if (xml != null && !xml.isBlank()) {
                    Files.writeString(localPath, xml,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    log.debug("Cached maven-metadata.xml → {}", localPath);
                    return Optional.of(xml);
                }
            } catch (RestClientException e) {
                log.debug("maven-metadata.xml not found at {}: {}", url, e.getMessage());
            } catch (IOException e) {
                log.warn("Failed to cache maven-metadata.xml for {}:{}: {}",
                        group, module, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Извлекает конкретную версию из {@code maven-metadata.xml}:
     * <ul>
     *   <li>Для {@code +}-версий (напр. {@code 2.+}): выбирает {@code <release>},
     *       если он начинается с нужного префикса; иначе — максимальную
     *       версию с нужным префиксом.</li>
     *   <li>Для range-версий (напр. {@code [2.1.0,)}): выбирает максимальную
     *       версию из {@code <versions>}, удовлетворяющую lower bound.</li>
     * </ul>
     */
    private Optional<String> resolveFromMetadata(String xml, String versionExpr,
                                                  String group, String module) {
        // Извлечаем все <version> теги и <release>
        List<String> versions = extractXmlElements(xml, "version");
        String release        = extractXmlElement(xml, "release");
        String latest         = extractXmlElement(xml, "latest");

        if (versions.isEmpty() && release == null && latest == null) {
            log.warn("maven-metadata.xml for {}:{} has no versions", group, module);
            return Optional.empty();
        }

        String trimmed = versionExpr.trim();

        // ── Обработка версий с "+" ─────────────────────────────────
        if (trimmed.contains("+")) {
            String prefix = trimmed.substring(0, trimmed.indexOf('+')).trim();
            // Без префикса ("+" или "latest.release") — берём release / latest
            if (prefix.isEmpty() || prefix.equals("latest")) {
                return Optional.ofNullable(release != null ? release : latest);
            }
            // Есть префикс (напр. "2.") — предпочитаем release, если он подходит
            if (release != null && release.startsWith(prefix)) {
                return Optional.of(release);
            }
            // Иначе — максимальная версия с данным префиксом
            return versions.stream()
                    .filter(v -> v.startsWith(prefix))
                    .max(Comparator.comparing(ArtifactorySourcesLoader::parseVersion));
        }

        // ── Maven range ───────────────────────────────────────────────
        if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
            VersionRange range = VersionRange.parse(trimmed);
            return versions.stream()
                    .filter(range::includes)
                    .max(Comparator.comparing(ArtifactorySourcesLoader::parseVersion));
        }

        return Optional.empty();
    }

    // ── Вспомогательные классы ───────────────────────────────────────────

    /**
     * Разбирает Maven version range ({@code [a,b]}, {@code (a,b)}, {@code [a,)}, ...) и
     * проверяет, удовлетворяет ли конкретная версия диапазону.
     * Поддерживается одинарный диапазон с открытым / закрытым левым/правым краем.
     * Пустые края (напр. {@code [2.1.0,)}) означают безграничные bound.
     */
    record VersionRange(String lower, boolean lowerInclusive,
                        String upper, boolean upperInclusive) {

        static VersionRange parse(String expr) {
            String s = expr.trim();
            boolean loInc = s.startsWith("[");
            boolean hiInc = s.endsWith("]");
            String inner = s.substring(1, s.length() - 1);
            int comma = inner.indexOf(',');
            String lo = comma >= 0 ? inner.substring(0, comma).trim() : inner.trim();
            String hi = comma >= 0 ? inner.substring(comma + 1).trim() : "";
            return new VersionRange(
                    lo.isEmpty()  ? null : lo,
                    loInc,
                    hi.isEmpty()  ? null : hi,
                    hiInc);
        }

        boolean includes(String version) {
            List<Integer> v = parseVersion(version);
            if (lower != null) {
                List<Integer> lo = parseVersion(lower);
                int cmp = compareVersionParts(v, lo);
                if (lowerInclusive ? cmp < 0 : cmp <= 0) return false;
            }
            if (upper != null) {
                List<Integer> hi = parseVersion(upper);
                int cmp = compareVersionParts(v, hi);
                if (upperInclusive ? cmp > 0 : cmp >= 0) return false;
            }
            return true;
        }
    }

    /**
     * Разбирает версию в список числовых сегментов (семантическое сравнение).
     * Нечисловые сегменты (напр. {@code -SNAPSHOT}) трактуются как 0.
     */
    static List<Integer> parseVersion(String version) {
        if (version == null) return List.of();
        List<Integer> parts = new ArrayList<>();
        for (String seg : version.split("[.\\-]")) {
            try {
                parts.add(Integer.parseInt(seg));
            } catch (NumberFormatException ignored) {
                parts.add(0);
            }
        }
        return parts;
    }

    private static int compareVersionParts(List<Integer> a, List<Integer> b) {
        int len = Math.max(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            int ai = i < a.size() ? a.get(i) : 0;
            int bi = i < b.size() ? b.get(i) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    // ── XML helpers (без DOM, чтобы не тянуть javax.xml) ────────────────

    /** Возвращает содержимое первого вхождения тега {@code <tag>...text...</tag>}. */
    private static String extractXmlElement(String xml, String tag) {
        int open  = xml.indexOf('<' + tag + '>');
        int close = xml.indexOf("</" + tag + '>');
        if (open < 0 || close < 0 || close <= open) return null;
        return xml.substring(open + tag.length() + 2, close).trim();
    }

    /** Возвращает содержимое всех вхождений тега {@code <tag>...text...</tag>}. */
    private static List<String> extractXmlElements(String xml, String tag) {
        List<String> result = new ArrayList<>();
        String open  = '<' + tag + '>';
        String close = "</" + tag + '>';
        int from = 0;
        while (true) {
            int s = xml.indexOf(open, from);
            int e = xml.indexOf(close, from);
            if (s < 0 || e < 0 || e <= s) break;
            result.add(xml.substring(s + open.length(), e).trim());
            from = e + close.length();
        }
        return result;
    }

    // ── Сердцевина parseApiDependencies: извлечение строки version из JSON ───────

    private static String extractVersionString(JsonNode versionNode) {
        if (versionNode == null || versionNode.isMissingNode()) return null;
        if (versionNode.isTextual()) return versionNode.asText(null);
        // {"requires": "x"} или {"prefers": "x"}
        String v = versionNode.path("requires").asText(null);
        if (v == null || v.isBlank()) v = versionNode.path("prefers").asText(null);
        return (v != null && v.isBlank()) ? null : v;
    }

    // ── Остальные helpers ────────────────────────────────────────────────

    private Optional<Path> downloadToFile(List<String> repoUrls, String relativePath,
                                          Path localPath, String fileType, String depLabel) {
        for (String repoUrl : repoUrls) {
            String base = repoUrl.endsWith("/")
                    ? repoUrl.substring(0, repoUrl.length() - 1) : repoUrl;
            String url  = base + '/' + relativePath;
            log.debug("Trying {}: {}", fileType, url);
            try {
                byte[] bytes = restClient.get().uri(url).retrieve().body(byte[].class);
                if (bytes != null && bytes.length > 0) {
                    Files.write(localPath, bytes,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    log.info("Downloaded {} ({} bytes) \u2192 {}: {}",
                            fileType, bytes.length, localPath, depLabel);
                    return Optional.of(localPath);
                }
            } catch (RestClientException e) {
                log.debug("Not found in {}: {} \u2014 {}", repoUrl, depLabel, e.getMessage());
            } catch (IOException e) {
                log.warn("Failed to save {} for {} to {}: {}",
                        fileType, depLabel, localPath, e.getMessage());
            }
        }
        log.debug("{} not found in any repo for: {}", fileType, depLabel);
        return Optional.empty();
    }

    private static String normalizeUrl(String raw) {
        return raw.replaceAll("['\"\u2018\u2019\u201c\u201d,)]+$", "");
    }
}
