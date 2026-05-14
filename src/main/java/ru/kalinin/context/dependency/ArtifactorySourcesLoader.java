package ru.kalinin.context.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Скачивает {@code *-sources.jar} из Artifactory.
 *
 * <h3>Определение URL Artifactory</h3>
 * <p>URL ищется в содержимом Gradle-файлов как строка без пробелов,
 * содержащая одновременно {@code http} и {@code artifactory}.
 * Например: {@code https://artifactory.company.com/artifactory/libs-release}
 *
 * <h3>Построение ссылки на sources.jar</h3>
 * <p>Итоговый URL: {@code <repoUrl>/<group/artifact/version/artifact-version-sources.jar>}
 * Используется стандартная Maven-структура пути.
 */
@Slf4j
@Component
public class ArtifactorySourcesLoader {

    /**
     * Ищет строки вида {@code https://...artifactory...} (без пробелов).
     * Захватывает URL целиком, включая возможный trailing-путь репозитория.
     */
    private static final Pattern ARTIFACTORY_URL_PATTERN = Pattern.compile(
            "(https?://\\S*artifactory\\S*)",
            Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;

    public ArtifactorySourcesLoader(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * Ищет Artifactory URL в переданных Gradle-файлах.
     *
     * @param gradleFileContents список содержимого *.gradle файлов проекта
     * @return первый найденный URL или {@code Optional.empty()}
     */
    public Optional<String> detectArtifactoryUrl(List<String> gradleFileContents) {
        for (String content : gradleFileContents) {
            Matcher m = ARTIFACTORY_URL_PATTERN.matcher(content);
            if (m.find()) {
                String url = m.group(1).replaceAll("['\",)]+$", ""); // убираем возможные замыкающие кавычки/скобки
                log.info("Detected Artifactory URL: {}", url);
                return Optional.of(url);
            }
        }
        log.warn("Artifactory URL not found in Gradle files");
        return Optional.empty();
    }

    /**
     * Скачивает байты {@code *-sources.jar} для указанной зависимости.
     *
     * @param artifactoryBaseUrl базовый URL репозитория Artifactory
     * @param dep                координаты зависимости (с явной версией)
     * @return байты jar-файла или {@code Optional.empty()} если недоступен
     */
    public Optional<byte[]> downloadSourcesJar(String artifactoryBaseUrl, DependencyCoordinate dep) {
        if (!dep.hasVersion()) {
            log.debug("Skipping BOM-managed dependency without version: {}", dep);
            return Optional.empty();
        }

        // Убираем trailing slash у base URL
        String base = artifactoryBaseUrl.endsWith("/")
                ? artifactoryBaseUrl.substring(0, artifactoryBaseUrl.length() - 1)
                : artifactoryBaseUrl;

        String url = base + '/' + dep.mavenRelativePath() + dep.sourcesJarName();
        log.debug("Downloading sources.jar: {}", url);

        try {
            byte[] bytes = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                log.debug("Empty response for sources.jar: {}", url);
                return Optional.empty();
            }
            log.info("Downloaded sources.jar ({} bytes): {}", bytes.length, dep);
            return Optional.of(bytes);
        } catch (RestClientException e) {
            log.debug("sources.jar not available for {}: {}", dep, e.getMessage());
            return Optional.empty();
        }
    }
}
