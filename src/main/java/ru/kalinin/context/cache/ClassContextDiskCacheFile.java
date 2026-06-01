package ru.kalinin.context.cache;

import ru.kalinin.context.model.ClassContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Содержимое файла {@code {module}__cache.json} на диске.
 */
public record ClassContextDiskCacheFile(
        Map<String, ClassContext> contexts
) {
    public ClassContextDiskCacheFile {
        contexts = contexts != null ? Map.copyOf(contexts) : Map.of();
    }

    public static ClassContextDiskCacheFile empty() {
        return new ClassContextDiskCacheFile(new LinkedHashMap<>());
    }
}
