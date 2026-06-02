package ru.kalinin.context.cache;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние кэша на время одного запроса {@code /api/context}.
 *
 * <p>Файлы модулей держатся в памяти; запись на диск — один раз при {@link #close()}.
 */
public final class ParseCacheRequestScope implements AutoCloseable {

    final ConcurrentHashMap<String, ParseCacheDiskFile> moduleFiles = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Boolean> warmedModules = new ConcurrentHashMap<>();
    final Set<String> dirtyModules = ConcurrentHashMap.newKeySet();

    private final ClassContextParseCache owner;

    ParseCacheRequestScope(ClassContextParseCache owner) {
        this.owner = owner;
    }

    @Override
    public void close() {
        owner.flushScope(this);
    }
}
