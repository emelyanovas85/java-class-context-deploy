package ru.kalinin.context.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.kalinin.context.model.ClassContext;

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
 * <p>В памяти хранится полный {@link ParseCacheEntry} (включая {@link ru.kalinin.context.model.ClassStructure}).
 * На диске — только {@link ClassContext} по qualified name внутри модуля/зависимости.
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
     * @param module   {@code src/main}, {@code src/test} или {@code groupId:artifactId:version}
     * @param qualifiedName полное имя типа
     */
    public Optional<ParseCacheEntry> get(String module, String qualifiedName) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = cacheKey(module, qualifiedName);
        ParseCacheEntry mem = memoryCache.getIfPresent(key);
        if (mem != null) {
            log.debug("Parse cache memory hit: {}", key);
            return Optional.of(mem);
        }
        return loadFromDisk(module, qualifiedName);
    }

    public void put(String module, String qualifiedName, ParseCacheEntry entry) {
        if (!enabled || entry == null || entry.template() == null) {
            return;
        }
        String key = cacheKey(module, qualifiedName);
        memoryCache.put(key, entry);
        persistTemplateToDisk(module, qualifiedName, ParseCacheEntry.toTemplate(entry.template()));
        log.debug("Parse cache stored: {}", key);
    }

    static String cacheKey(String module, String qualifiedName) {
        return module + KEY_SEPARATOR + qualifiedName;
    }

    static String diskFileName(String module) {
        return sanitizeModule(module) + CACHE_SUFFIX;
    }

    /**
     * Имя файла кэша: модуль/зависимость с заменой {@code :} и {@code /} на {@code __}.
     */
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
                ClassContextDiskCacheFile disk = objectMapper.readValue(file.toFile(), ClassContextDiskCacheFile.class);
                ClassContext template = disk.contexts().get(qualifiedName);
                if (template == null) {
                    return Optional.empty();
                }
                ParseCacheEntry entry = ParseCacheEntry.fromDiskTemplate(template);
                memoryCache.put(cacheKey(module, qualifiedName), entry);
                log.debug("Parse cache disk hit: {} in {}", qualifiedName, file.getFileName());
                return Optional.of(entry);
            } catch (IOException e) {
                log.warn("Failed to read parse cache {}: {}", file, e.getMessage());
                return Optional.empty();
            }
        }
    }

    private void persistTemplateToDisk(String module, String qualifiedName, ClassContext template) {
        Path file = cacheDir.resolve(diskFileName(module));
        Object lock = moduleLocks.computeIfAbsent(module, m -> new Object());
        synchronized (lock) {
            try {
                ClassContextDiskCacheFile disk = Files.isRegularFile(file)
                        ? objectMapper.readValue(file.toFile(), ClassContextDiskCacheFile.class)
                        : ClassContextDiskCacheFile.empty();
                Map<String, ClassContext> merged = new LinkedHashMap<>(disk.contexts());
                merged.put(qualifiedName, template);
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(file.toFile(), new ClassContextDiskCacheFile(merged));
            } catch (IOException e) {
                log.warn("Failed to write parse cache {}: {}", file, e.getMessage());
            }
        }
    }
}
