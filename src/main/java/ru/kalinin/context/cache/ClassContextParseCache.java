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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Двухуровневый кэш результатов парсинга: память (Caffeine, TTL) + диск ({@code {module}__cache.json}).
 *
 * <p>На диске и в памяти хранится полный {@link ParseCacheEntry}
 * ({@link ru.kalinin.context.model.ClassStructure}, {@link ru.kalinin.context.model.StructureNode},
 * шаблон {@link ru.kalinin.context.model.ClassContext}).
 *
 * <p>Кэшируются только типы из внешних jar ({@code groupId:artifactId:version}).
 * Исходники репозитория ({@code src/main}, {@code src/test}) не кэшируются.
 *
 * <h3>Оптимизации</h3>
 * <ul>
 *   <li>При первом обращении к модулю в рамках запроса — одно чтение {@code __cache.json}
 *       и прогрев Caffeine всеми записями файла.</li>
 *   <li>Содержимое файла модуля держится в {@link ParseCacheRequestScope} без synchronized при чтении.</li>
 *   <li>Запись на диск откладывается до {@link ParseCacheRequestScope#close()} (конец {@code buildContext}).</li>
 * </ul>
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

    /** lock только для первичной загрузки файла модуля с диска (один раз на модуль за запрос) */
    private final ConcurrentHashMap<String, Object> moduleLoadLocks = new ConcurrentHashMap<>();

    private final AtomicReference<ParseCacheRequestScope> activeScope = new AtomicReference<>();

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
     * Открывает scope на время обработки одного MR-запроса (параллельные потоки BFS делят один scope).
     */
    public ParseCacheRequestScope beginScope() {
        ParseCacheRequestScope scope = new ParseCacheRequestScope(this);
        activeScope.set(scope);
        return scope;
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
        ensureModuleAvailable(module);

        String key = cacheKey(module, qualifiedName);
        ParseCacheEntry mem = memoryCache.getIfPresent(key);
        if (mem != null && mem.hasParsedStructures()) {
            log.debug("Parse cache memory hit: {}", key);
            return Optional.of(mem);
        }
        return Optional.empty();
    }

    public void put(String module, String qualifiedName, ParseCacheEntry entry) {
        if (!enabled || !isCacheableModule(module) || entry == null || !entry.hasParsedStructures()) {
            return;
        }
        ParseCacheRequestScope scope = activeScope.get();
        if (scope == null) {
            putWithoutScope(module, qualifiedName, entry);
            return;
        }

        String key = cacheKey(module, qualifiedName);
        memoryCache.put(key, entry);

        ParseCacheEntry diskEntry = entry.template() != null
                ? new ParseCacheEntry(entry.parsed(), entry.fileNodes(), ParseCacheEntry.toTemplate(entry.template()))
                : entry;

        ensureModuleAvailable(module);
        scope.moduleFiles.compute(module, (m, disk) -> mergeEntry(disk, qualifiedName, diskEntry));
        scope.dirtyModules.add(module);
        log.debug("Parse cache stored (deferred disk): {}", key);
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

    void flushScope(ParseCacheRequestScope scope) {
        if (!enabled || scope == null) {
            return;
        }
        int flushed = scope.dirtyModules.size();
        for (String module : scope.dirtyModules) {
            ParseCacheDiskFile disk = scope.moduleFiles.get(module);
            if (disk != null) {
                writeModuleFile(module, disk);
            }
        }
        activeScope.compareAndSet(scope, null);
        log.debug("Parse cache scope closed, flushed {} module(s)", flushed);
    }

    // ── module load / warm ─────────────────────────────────────────────────────

    private void ensureModuleAvailable(String module) {
        ParseCacheRequestScope scope = activeScope.get();
        if (scope != null) {
            ensureModuleInScope(scope, module);
            return;
        }
        ensureModuleWithoutScope(module);
    }

    private void ensureModuleInScope(ParseCacheRequestScope scope, String module) {
        if (scope.warmedModules.containsKey(module)) {
            return;
        }
        Object lock = moduleLoadLocks.computeIfAbsent(module, m -> new Object());
        synchronized (lock) {
            if (scope.warmedModules.containsKey(module)) {
                return;
            }
            ParseCacheDiskFile disk = scope.moduleFiles.computeIfAbsent(module, this::readModuleFileFromDisk);
            warmCaffeine(module, disk);
            scope.warmedModules.put(module, Boolean.TRUE);
        }
    }

    private void ensureModuleWithoutScope(String module) {
        Object lock = moduleLoadLocks.computeIfAbsent(module, m -> new Object());
        synchronized (lock) {
            ParseCacheDiskFile disk = readModuleFileFromDisk(module);
            warmCaffeine(module, disk);
        }
    }

    private void warmCaffeine(String module, ParseCacheDiskFile disk) {
        int count = 0;
        for (Map.Entry<String, ParseCacheEntry> e : disk.entries().entrySet()) {
            if (e.getValue() != null && e.getValue().hasParsedStructures()) {
                memoryCache.put(cacheKey(module, e.getKey()), e.getValue());
                count++;
            }
        }
        if (count > 0) {
            log.info("Parse cache warmed {} entries for module {}", count, module);
        }
    }

    private ParseCacheDiskFile readModuleFileFromDisk(String module) {
        Path file = cacheDir.resolve(diskFileName(module));
        if (!Files.isRegularFile(file)) {
            return ParseCacheDiskFile.empty();
        }
        try {
            ParseCacheDiskFile disk = objectMapper.readValue(file.toFile(), ParseCacheDiskFile.class);
            log.debug("Parse cache loaded {} entries from {}", disk.entries().size(), file.getFileName());
            return disk;
        } catch (IOException e) {
            log.warn("Failed to read parse cache {}: {}", file, e.getMessage());
            return ParseCacheDiskFile.empty();
        }
    }

    private static ParseCacheDiskFile mergeEntry(
            ParseCacheDiskFile disk, String qualifiedName, ParseCacheEntry entry) {
        Map<String, ParseCacheEntry> merged = new LinkedHashMap<>(
                disk != null ? disk.entries() : Map.of());
        merged.put(qualifiedName, entry);
        return new ParseCacheDiskFile(merged);
    }

    private void writeModuleFile(String module, ParseCacheDiskFile disk) {
        Path file = cacheDir.resolve(diskFileName(module));
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(file.toFile(), disk);
            log.debug("Parse cache flushed {} entries to {}", disk.entries().size(), file.getFileName());
        } catch (IOException e) {
            log.warn("Failed to write parse cache {}: {}", file, e.getMessage());
        }
    }

    /** Fallback без request-scope (тесты): сразу пишет на диск. */
    private void putWithoutScope(String module, String qualifiedName, ParseCacheEntry entry) {
        String key = cacheKey(module, qualifiedName);
        memoryCache.put(key, entry);
        ParseCacheEntry diskEntry = entry.template() != null
                ? new ParseCacheEntry(entry.parsed(), entry.fileNodes(), ParseCacheEntry.toTemplate(entry.template()))
                : entry;
        ParseCacheDiskFile disk = readModuleFileFromDisk(module);
        writeModuleFile(module, mergeEntry(disk, qualifiedName, diskEntry));
        log.debug("Parse cache stored: {}", key);
    }
}
