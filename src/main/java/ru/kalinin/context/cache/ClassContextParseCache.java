package ru.kalinin.context.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Двухуровневый кэш результатов парсинга: память (Caffeine, TTL) + диск ({@code {module}__cache.json}).
 *
 * <p>На диске и в памяти хранится полный {@link ParseCacheEntry}
 * ({@link ru.kalinin.context.model.ClassStructure}, {@link ru.kalinin.context.model.StructureNode},
 * шаблон {@link ru.kalinin.context.model.ClassContext}).
 *
 * <p>Кэшируются только типы из внешних jar ({@code groupId:artifactId:version}).
 * Исходники репозитория ({@code src/main}, {@code src/test}) не кэшируются.
 */
@Slf4j
@Component
public class ClassContextParseCache {

    static final String CACHE_SUFFIX = "__cache.json";
    static final String KEY_SEPARATOR = "::";

    private final boolean enabled;
    private final Path cacheDir;
    private final ObjectMapper objectMapper;
    private final Cache<String, ParseCacheEntry> memoryCache;

    /** module → lock для read-merge-write файла */
    private final ConcurrentHashMap<String, Object> moduleLocks = new ConcurrentHashMap<>();

    public ClassContextParseCache(
            ObjectMapper objectMapper,
            @Qualifier("parseCacheMemory") Cache<String, ParseCacheEntry> memoryCache,
            @Value("${app.parse-cache.enabled:true}") boolean enabled,
            @Value("${app.parse-cache.dir:artifacts/parse-cache}") String cacheDirPath) {
        this.objectMapper = objectMapper;
        this.memoryCache = memoryCache;
        this.enabled = enabled;
        this.cacheDir = Path.of(cacheDirPath);
        if (enabled) {
            try {
                Files.createDirectories(cacheDir);
                log.info("Parse cache directory: {}", cacheDir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("Cannot create parse cache directory {}: {}", cacheDir, e.getMessage());
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Только jar-зависимости ({@code groupId:artifactId:version}), не {@code src/main} / {@code src/test}.
     */
    public static boolean isCacheableModule(String module) {
        return module != null && module.indexOf(':') >= 0;
    }

    /**
     * @param module        {@code groupId:artifactId:version} для jar; repo-модули игнорируются
     * @param qualifiedName полное имя типа
     */
    public Optional<ParseCacheEntry> get(String module, String qualifiedName) {
        if (!enabled || !isCacheableModule(module)) {
            return Optional.empty();
        }
        String key = cacheKey(module, qualifiedName);
        ParseCacheEntry mem = memoryCache.getIfPresent(key);
        if (mem != null && mem.hasParsedStructures()) {
            log.debug("Parse cache memory hit: {}", key);
            return Optional.of(mem);
        }
        return loadFromDisk(module, qualifiedName);
    }

    public void put(String module, String qualifiedName, ParseCacheEntry entry) {
        if (!enabled || !isCacheableModule(module) || entry == null || !entry.hasParsedStructures()) {
            return;
        }
        String key = cacheKey(module, qualifiedName);
        memoryCache.put(key, entry);
        ParseCacheEntry diskEntry = entry.template() != null
                ? new ParseCacheEntry(entry.parsed(), entry.fileNodes(), ParseCacheEntry.toTemplate(entry.template()))
                : entry;
        persistEntryToDisk(module, qualifiedName, diskEntry);
        log.debug("Parse cache stored: {}", key);
    }

    static String cacheKey(String module, String qualifiedName) {
        return module + KEY_SEPARATOR + qualifiedName;
    }

    static String diskFileName(String module) {
        return sanitizeModule(module) + CACHE_SUFFIX;
    }

    static String sanitizeModule(String module) {
        return module.replace("/", "__").replace(":", "__");
    }

    private Optional<ParseCacheEntry> loadFromDisk(String module, String qualifiedName) {
        Path file = cacheDir.resolve(diskFileName(module));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Object lock = moduleLocks.computeIfAbsent(module, m -> new Object());
        synchronized (lock) {
            try {
                ParseCacheDiskFile disk = objectMapper.readValue(file.toFile(), ParseCacheDiskFile.class);
                ParseCacheEntry entry = disk.entries().get(qualifiedName);
                if (entry == null || !entry.hasParsedStructures()) {
                    return Optional.empty();
                }
                memoryCache.put(cacheKey(module, qualifiedName), entry);
                log.debug("Parse cache disk hit: {} in {}", qualifiedName, file.getFileName());
                return Optional.of(entry);
            } catch (IOException e) {
                log.warn("Failed to read parse cache {}: {}", file, e.getMessage());
                return Optional.empty();
            }
        }
    }

    private void persistEntryToDisk(String module, String qualifiedName, ParseCacheEntry entry) {
        Path file = cacheDir.resolve(diskFileName(module));
        Object lock = moduleLocks.computeIfAbsent(module, m -> new Object());
        synchronized (lock) {
            try {
                ParseCacheDiskFile disk = Files.isRegularFile(file)
                        ? objectMapper.readValue(file.toFile(), ParseCacheDiskFile.class)
                        : ParseCacheDiskFile.empty();
                Map<String, ParseCacheEntry> merged = new LinkedHashMap<>(disk.entries());
                merged.put(qualifiedName, entry);
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(file.toFile(), new ParseCacheDiskFile(merged));
            } catch (IOException e) {
                log.warn("Failed to write parse cache {}: {}", file, e.getMessage());
            }
        }
    }
}
