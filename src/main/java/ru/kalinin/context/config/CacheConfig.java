package ru.kalinin.context.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.kalinin.context.cache.ParseCacheEntry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Конфигурация Caffeine-кэша для файлового индекса GitLab.
 *
 * <p>Ключ кэша: {@code "gitlabUrl::projectId::branch"}.
 * Значение: {@code Map<simpleName, List<fullPath>>}.
 *
 * <p>Используется {@code expireAfterAccess}: запись живёт
 * {@code app.file-index-cache.expire-after-access-minutes} минут с момента
 * последнего чтения или записи (read или write).
 */
@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, Map<String, List<String>>> fileIndexCache(
            @Value("${app.file-index-cache.expire-after-access-minutes:15}")
            int expireMinutes) {
        return Caffeine.newBuilder()
                .expireAfterAccess(expireMinutes, TimeUnit.MINUTES)
                .recordStats()          // попадает в actuator если подключить
                .build();
    }

    /**
     * Кэш парсинга в памяти. Ключ: {@code module::qualifiedName}.
     */
    @Bean
    @Qualifier("parseCacheMemory")
    public Cache<String, ParseCacheEntry> parseCacheMemory(
            @Value("${app.parse-cache.expire-after-access-minutes:60}")
            int expireMinutes) {
        return Caffeine.newBuilder()
                .expireAfterAccess(expireMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
