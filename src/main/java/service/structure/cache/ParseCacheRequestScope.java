package service.structure.cache;

import service.structure.parser.ParsedJavaFile;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние кэша на время одного запроса structure API.
 */
public final class ParseCacheRequestScope implements AutoCloseable {

    /** Загруженные с диска файлы модулей (база для flush) */
    final ConcurrentHashMap<String, ParseCacheDiskFile> moduleFiles = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Boolean> warmedModules = new ConcurrentHashMap<>();
    final Set<String> dirtyModules = ConcurrentHashMap.newKeySet();

    /** jar: ключ {@link ClassContextParseCache#cacheKey} → запись (дедупликация по ключу) */
    final ConcurrentHashMap<String, ParseCacheEntry> pendingJarEntries = new ConcurrentHashMap<>();

    /** repo: один раз читаем .java с GitLab на путь */
    final ConcurrentHashMap<String, String> repoFileContent = new ConcurrentHashMap<>();

    /** общий результат parse одного файла (jar и repo) */
    final ConcurrentHashMap<String, ParsedJavaFile> parsedByFile = new ConcurrentHashMap<>();

    private final ClassContextParseCache owner;

    ParseCacheRequestScope(ClassContextParseCache owner) {
        this.owner = owner;
    }

    @Override
    public void close() {
        owner.flushScope(this);
    }
}
