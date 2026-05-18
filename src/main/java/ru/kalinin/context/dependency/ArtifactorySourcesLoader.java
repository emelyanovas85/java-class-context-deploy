package ru.kalinin.context.dependency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Скачивает {@code *-sources.jar} из Artifactory.
 *
 * <h3>Кэширование</h3>
 * <p>Скачанные jar-файлы кэшируются в памяти по {@link DependencyCoordinate}.
 * Повторный вызов {@link #downloadSourcesJar} для той же зависимости
 * возвращает результат из кэша без сетевого запроса.
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

    /** Кэш: координаты зависимости → байты скачанного sources.jar. */
    private final ConcurrentHashMap<DependencyCoordinate, byte[]> jarCache = new ConcurrentHashMap<>();

    private final RestClient restClient;

    public ArtifactorySourcesLoader(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
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
     * Возвращает байты {@code *-sources.jar} для указанной зависимости.
     *
     * <p>Результат кэшируется: повторный вызов для той же зависимости
     * не выполняет сетевой запрос.
     *
     * <p>Перебирает все переданные repo URL до первого успешного ответа.
     *
     * @param artifactoryRepoUrls список repo URL из {@link #detectArtifactoryUrls}
     * @param dep                 координаты зависимости (с явной версией)
     * @return байты jar-файла или {@code Optional.empty()} если ни в одном репозитории не нашлось
     */
    public Optional<byte[]> downloadSourcesJar(List<String> artifactoryRepoUrls,
                                               DependencyCoordinate dep) {
        if (!dep.hasVersion()) {
            log.debug("Skipping BOM-managed dependency without version: {}", dep);
            return Optional.empty();
        }

        byte[] cached = jarCache.get(dep);
        if (cached != null) {
            log.debug("sources.jar cache hit for: {}", dep);
            return Optional.of(cached);
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
                    log.info("Downloaded sources.jar ({} bytes) from {}: {}", bytes.length, repoUrl, dep);
                    jarCache.put(dep, bytes);
                    return Optional.of(bytes);
                }
            } catch (RestClientException e) {
                log.debug("Not found in {}: {} — {}", repoUrl, dep, e.getMessage());
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
