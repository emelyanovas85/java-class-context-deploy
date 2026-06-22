package service.structure.cache;

import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import service.structure.model.ClassContext;
import service.structure.parser.ParsedJavaFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Двухуровневый кэш результатов парсинга: память (Caffeine, TTL) + диск ({@code {module}__cache.json}).
 *
 * <p>Кэшируются на диск только jar ({@code groupId:artifactId:version}).
 * В память публикуются и repo-типы — для параллельного BFS без повторного GitLab/parse.
 */
@Slf4j
@Component
public class ClassContextParseCache {

    static final String CACHE_SUFFIX = "__cache.json";
    static final String KEY_SEPARATOR = "::";
    static final String FILE_KEY_PREFIX = "::file::";

    private final boolean enabled;
    private final Path cacheDir;
    private final ObjectMapper objectMapper;
    private final Cache<String, ParseCacheEntry> memoryCache;

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

    public ParseCacheRequestScope beginScope() {
        ParseCacheRequestScope scope = new ParseCacheRequestScope(this);
        activeScope.set(scope);
        return scope;
    }

    public static boolean isCacheableModule(String module) {
        return module != null && module.indexOf(':') >= 0;
    }

    public static String cacheKey(String module, String qualifiedName) {
        return module + KEY_SEPARATOR + qualifiedName;
    }

    public static String fileParseKey(String module, String filePath) {
        return module + FILE_KEY_PREFIX + filePath;
    }

    public static String repoContentKey(String gitlabUrl, String projectId, String branch, String filePath) {
        return gitlabUrl + KEY_SEPARATOR + projectId + KEY_SEPARATOR + branch + KEY_SEPARATOR + filePath;
    }

    static String diskFileName(String module) {
        return sanitizeModule(module) + CACHE_SUFFIX;
    }

    static String sanitizeModule(String module) {
        return module.replace("/", "__").replace(":", "__");
    }

    /**
     * Чтение: сначала Caffeine (все модули), затем прогрев с диска для jar.
     */
    public Optional<ParseCacheEntry> get(String module, String qualifiedName) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = cacheKey(module, qualifiedName);
        ParseCacheEntry mem = memoryCache.getIfPresent(key);
        if (mem != null && mem.hasParsedStructures()) {
            log.debug("Parse cache memory hit: {}", key);
            return Optional.of(mem);
        }
        if (!isCacheableModule(module)) {
            return Optional.empty();
        }
        ensureModuleAvailable(module);
        mem = memoryCache.getIfPresent(key);
        if (mem != null && mem.hasParsedStructures()) {
            log.debug("Parse cache memory hit: {}", key);
            return Optional.of(mem);
        }
        return Optional.empty();
    }

    public void put(String module, String qualifiedName, ParseCacheEntry entry) {
        if (!enabled || entry == null || !entry.hasParsedStructures()) {
            return;
        }
        ClassContext template = entry.template();
        publishParsed(
                module,
                qualifiedName,
                entry.parsed(),
                entry.fileNodes(),
                template != null ? ParseCacheEntry.toTemplate(template) : null);
    }

    /**
     * Публикует результат parse в Caffeine сразу (до flush).
     * Для jar накапливает в {@link ParseCacheRequestScope#pendingJarEntries} с дедупликацией по ключу.
     */
    public void publishParsed(
            String module,
            String qualifiedName,
            java.util.List<service.structure.model.ClassStructure> parsed,
            java.util.List<service.structure.model.StructureNode> fileNodes,
            ClassContext template) {
        if (!enabled || parsed == null || parsed.isEmpty()) {
            return;
        }
        String key = cacheKey(module, qualifiedName);

        ParseCacheRequestScope scope = activeScope.get();
        if (scope != null && isCacheableModule(module)) {
            ensureModuleAvailable(module);
            scope.pendingJarEntries.compute(key, (k, prev) -> {
                ClassContext mergedTemplate = template != null
                        ? template
                        : prev != null ? prev.template() : null;
                ParseCacheEntry next = new ParseCacheEntry(parsed, fileNodes, mergedTemplate);
                memoryCache.put(k, next);
                if (!Objects.equals(prev, next)) {
                    scope.dirtyModules.add(module);
                    log.debug("Parse cache stored (deferred disk): {}", k);
                }
                return next;
            });
            return;
        }

        ClassContext mergedTemplate = template;
        if (scope != null) {
            ParseCacheEntry prev = scope.pendingJarEntries.get(key);
            if (mergedTemplate == null && prev != null) {
                mergedTemplate = prev.template();
            }
        }
        ParseCacheEntry newEntry = new ParseCacheEntry(parsed, fileNodes, mergedTemplate);
        memoryCache.put(key, newEntry);

        if (!isCacheableModule(module)) {
            return;
        }

        if (scope == null) {
            putWithoutScope(module, qualifiedName, newEntry);
        }
    }

    /**
     * Один parse на файл за запрос (параллельные потоки делят результат).
     */
    public Optional<ParsedJavaFile> getParsedFile(String module, String filePath) {
        ParseCacheRequestScope scope = activeScope.get();
        if (scope == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(scope.parsedByFile.get(fileParseKey(module, filePath)));
    }

    public void storeParsedFile(String module, String filePath, ParsedJavaFile file) {
        ParseCacheRequestScope scope = activeScope.get();
        if (scope == null || file == null) {
            return;
        }
        scope.parsedByFile.put(fileParseKey(module, filePath), file);
    }

    /**
     * Содержимое .java из GitLab — один HTTP на путь за запрос.
     */
    public Optional<String> getRepoFileContent(
            String gitlabUrl, String projectId, String branch, String filePath) {
        ParseCacheRequestScope scope = activeScope.get();
        if (scope == null) {
            return Optional.empty();
        }
        String content = scope.repoFileContent.get(repoContentKey(gitlabUrl, projectId, branch, filePath));
        return content != null && !content.isEmpty() ? Optional.of(content) : Optional.empty();
    }

    public void storeRepoFileContent(
            String gitlabUrl, String projectId, String branch, String filePath, String content) {
        ParseCacheRequestScope scope = activeScope.get();
        if (scope == null || content == null) {
            return;
        }
        scope.repoFileContent.put(repoContentKey(gitlabUrl, projectId, branch, filePath), content);
    }

    void flushScope(ParseCacheRequestScope scope) {
        if (!enabled || scope == null) {
            return;
        }
        int flushed = scope.dirtyModules.size();
        for (String module : scope.dirtyModules) {
            ParseCacheDiskFile base = scope.moduleFiles.getOrDefault(module, ParseCacheDiskFile.empty());
            ParseCacheDiskFile merged = base;
            String prefix = module + KEY_SEPARATOR;
            for (Map.Entry<String, ParseCacheEntry> e : scope.pendingJarEntries.entrySet()) {
                if (!e.getKey().startsWith(prefix)) {
                    continue;
                }
                String qName = e.getKey().substring(prefix.length());
                merged = mergeEntry(merged, qName, e.getValue());
            }
            writeModuleFile(module, merged);
            scope.moduleFiles.put(module, merged);
        }
        activeScope.compareAndSet(scope, null);
        log.debug("Parse cache scope closed, flushed {} module(s)", flushed);
    }

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

    private void putWithoutScope(String module, String qualifiedName, ParseCacheEntry entry) {
        memoryCache.put(cacheKey(module, qualifiedName), entry);
        ParseCacheDiskFile disk = readModuleFileFromDisk(module);
        writeModuleFile(module, mergeEntry(disk, qualifiedName, entry));
        log.debug("Parse cache stored: {}", cacheKey(module, qualifiedName));
    }
}
