package ru.kalinin.context.dependency;

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
 * Скачивает {@code *-sources.jar} из Artifactory и сохраняет их на диск.
 *
 * <h3>Кэширование на диске</h3>
 * <p>Каждый скачанный jar сохраняется в директорию {@code artifactsDir}
 * (по умолчанию {@code /artifacts}) под имене
 * {@code groupId__artifactId__version-sources.jar}.
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

    /** Директория для хранения скачанных jar-файлов. */
    private final Path artifactsDir;

    private final RestClient restClient;

    public ArtifactorySourcesLoader(
            RestClient.Builder restClientBuilder,
            @Value("${app.artifacts-dir:/artifacts}") String artifactsDirPath) {
        this.restClient = restClientBuilder.build();
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

        for (String repoUrl : artifactoryRepoUrls) {
            String base = repoUrl.endsWith("/")
                    ? repoUrl.substring(0, repoUrl.length() - 1)
                    : repoUrl;
            String url = base + '/' + relativePath;
            log.debug("Trying sources.jar: {}", url);

            try {
                byte[] bytes = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(byte[].class);
                if (bytes != null && bytes.length > 0) {
                    Files.write(localPath, bytes,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    log.info("Downloaded and saved sources.jar ({} bytes) \u2192 {}: {}",
                            bytes.length, localPath, dep);
                    return Optional.of(localPath);
                }
            } catch (RestClientException e) {
                log.debug("Not found in {}: {} \u2014 {}", repoUrl, dep, e.getMessage());
            } catch (IOException e) {
                log.warn("Failed to save sources.jar for {} to {}: {}", dep, localPath, e.getMessage());
            }
        }

        log.debug("sources.jar not found in any repo for: {}", dep);
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Удаляет замыкающие нежелательные символы с конца URL:
     * прямые и типографские кавычки, запятые, скобки.
     */
    private static String normalizeUrl(String raw) {
        return raw.replaceAll("['\"\u2018\u2019\u201c\u201d,)]+$", "");
    }
}
