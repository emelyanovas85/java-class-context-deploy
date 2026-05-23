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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Скачивает {@code *-sources.jar} и {@code *.module} из Artifactory и сохраняет их на диск.
 *
 * <h3>Кэширование на диске</h3>
 * <p>Каждый скачанный файл (jar или .module) сохраняется в директорию {@code artifactsDir}
 * (по умолчанию {@code /artifacts}).
 * Двойное подчёркивание ({@code __}) как разделитель устраняет неоднозначность
 * при парсинге, возникающую когда groupId, artifactId или version сами
 * содержат дефисы.
 * При повторном запросе файл читается с диска без сетевого вызова.
 * Байты в памяти не удерживаются — метод возвращает {@link Path}.
 *
 * <h3>Определение URL Artifactory</h3>
 * <p>Ищем все {@code http(s)://...artifactory...} URL во всех Gradle-файлах.
 * Один артефакт может лежать в любом из репозиториев, поэтому собираем
 * <b>все</b> найденные URL и перебираем их при скачивании.
 *
 * <h3>Построение ссылки на sources.jar</h3>
 * <p>Итоговый URL: {@code <repoUrl>/<group/artifact/version/artifact-version-sources.jar>}
 * Используется стандартная Maven-структура пути.
 */
@Slf4j
@Component
public class ArtifactorySourcesLoader {

    /**
     * Находит все вхождения вида {@code http(s)://...<anything>...artifactory...<anything>}.
     * Паттерн останавливается на первом символе, не являющемся частью URL:
     * пробел, перенос строки, прямые/типографские кавычки, запятая, закрывающая скобка.
     */
    private static final Pattern ARTIFACTORY_URL_PATTERN = Pattern.compile(
            "(https?://[^\\s'\"\u2018\u2019\u201c\u201d,)]+artifactory[^\\s'\"\u2018\u2019\u201c\u201d,)]*)",
            Pattern.CASE_INSENSITIVE
    );

    /** Директория для хранения скачанных файлов (jar и .module). */
    private final Path artifactsDir;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ArtifactorySourcesLoader(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.artifacts-dir:artifacts}") String artifactsDirPath) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.artifactsDir = Path.of(artifactsDirPath);
        try {
            Files.createDirectories(artifactsDir);
            log.info("Artifacts directory: {}", artifactsDir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Cannot create artifacts directory {}: {}", artifactsDir, e.getMessage());
        }
    }

    /**
     * Собирает все Artifactory repo URL из переданных Gradle-файлов.
     *
     * <p>URL дедуплицируются — один URL может быть упомянут в нескольких файлах.
     *
     * @param gradleFileContents список содержимого *.gradle файлов проекта
     * @return уникальные URL всех найденных Artifactory-репозиториев, или пустой список
     */
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

    /**
     * Возвращает путь к локально сохранённому {@code *-sources.jar} для указанной зависимости.
     *
     * <p>Если jar уже присутствует в {@code artifactsDir} — возвращает его путь без
     * обращения к сети. Иначе скачивает, сохраняет на диск и возвращает путь.
     * Байты в памяти после возврата не удерживаются.
     *
     * <p>Перебирает все переданные repo URL до первого успешного ответа.
     *
     * @param artifactoryRepoUrls список repo URL из {@link #detectArtifactoryUrls}
     * @param dep                 координаты зависимости (с явной версией)
     * @return путь к jar-файлу на диске или {@code Optional.empty()} если не найдено
     */
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
                "sources.jar", dep.toString())
                .map(p -> p);
    }

    /**
     * Возвращает путь к локально сохранённому {@code .module} файлу для указанной зависимости.
     *
     * <p>Кэшируется на диск аналогично sources.jar.
     *
     * @param artifactoryRepoUrls список repo URL из {@link #detectArtifactoryUrls}
     * @param dep                 координаты зависимости (с явной версией)
     * @return путь к .module-файлу на диске или {@code Optional.empty()} если не найдено
     */
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
     * Парсит содержимое Gradle Module Metadata ({@code .module}) файла
     * и выводит список api-зависимостей из варианта {@code apiElements}.
     *
     * <p>Структура .module:
     * <pre>
     * {
     *   "variants": [
     *     {
     *       "name": "apiElements",
     *       "dependencies": [
     *         { "group": "com.example", "module": "some-lib", "version": { "requires": "1.2.3" } }
     *       ]
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param moduleFilePath путь к скачанному .module-файлу
     * @param parentDep      родительская зависимость (для логов)
     * @return список api-зависимостей, может быть пустым
     */
    public List<DependencyCoordinate> parseApiDependencies(Path moduleFilePath,
                                                           DependencyCoordinate parentDep) {
        List<DependencyCoordinate> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(moduleFilePath.toFile());
            JsonNode variants = root.path("variants");
            for (JsonNode variant : variants) {
                if (!"apiElements".equals(variant.path("name").asText())) {
                    continue;
                }
                JsonNode dependencies = variant.path("dependencies");
                for (JsonNode dep : dependencies) {
                    String group   = dep.path("group").asText(null);
                    String module  = dep.path("module").asText(null);
                    // version может быть объектом {"requires": "x"} или строкой
                    JsonNode versionNode = dep.path("version");
                    String version = null;
                    if (versionNode.isTextual()) {
                        version = versionNode.asText(null);
                    } else if (!versionNode.isMissingNode()) {
                        version = versionNode.path("requires").asText(null);
                        if (version == null || version.isBlank()) {
                            version = versionNode.path("prefers").asText(null);
                        }
                    }
                    if (group != null && module != null) {
                        result.add(new DependencyCoordinate(group, module, version));
                        log.debug("Found api dep in .module of {}: {}:{}", parentDep, group, module);
                    }
                }
                break; // apiElements нашли, дальше не идём
            }
        } catch (IOException e) {
            log.warn("Failed to parse .module file {}: {}", moduleFilePath, e.getMessage());
        }
        log.debug("Parsed {} api deps from .module of {}", result.size(), parentDep);
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Скачивает файл по перебору repoUrls и сохраняет на диск.
     * Возвращает путь при успехе или {@code Optional.empty()}.
     */
    private Optional<Path> downloadToFile(List<String> repoUrls, String relativePath,
                                          Path localPath, String fileType, String depLabel) {
        for (String repoUrl : repoUrls) {
            String base = repoUrl.endsWith("/")
                    ? repoUrl.substring(0, repoUrl.length() - 1)
                    : repoUrl;
            String url = base + '/' + relativePath;
            log.debug("Trying {}: {}", fileType, url);

            try {
                byte[] bytes = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(byte[].class);
                if (bytes != null && bytes.length > 0) {
                    Files.write(localPath, bytes,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    log.info("Downloaded and saved {} ({} bytes) \u2192 {}: {}",
                            fileType, bytes.length, localPath, depLabel);
                    return Optional.of(localPath);
                }
            } catch (RestClientException e) {
                log.debug("Not found in {}: {} \u2014 {}", repoUrl, depLabel, e.getMessage());
            } catch (IOException e) {
                log.warn("Failed to save {} for {} to {}: {}", fileType, depLabel, localPath, e.getMessage());
            }
        }
        log.debug("{} not found in any repo for: {}", fileType, depLabel);
        return Optional.empty();
    }

    /**
     * Удаляет замыкающие нежелательные символы с конца URL:
     * прямые и типографские кавычки, запятые, скобки.
     */
    private static String normalizeUrl(String raw) {
        return raw.replaceAll("['\"\u2018\u2019\u201c\u201d,)]+$", "");
    }
}
