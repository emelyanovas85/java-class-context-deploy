package service.structure.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Содержимое файла {@code {module}__cache.json}: полные {@link ParseCacheEntry} по qualified name.
 */
public record ParseCacheDiskFile(
        Map<String, ParseCacheEntry> entries
) {
    public ParseCacheDiskFile {
        entries = entries != null ? Map.copyOf(entries) : Map.of();
    }

    public static ParseCacheDiskFile empty() {
        return new ParseCacheDiskFile(new LinkedHashMap<>());
    }
}
