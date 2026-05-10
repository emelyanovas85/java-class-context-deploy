package ru.kalinin.context.model;

import java.util.List;

/**
 * Ответ эндпоинта POST /api/context.
 *
 * @param mergeRequest        метаданные мёрж-реквеста
 * @param classes             структуры всех проанализированных классов,
 *                            сортированные по полю {@code contextLevel}
 * @param requestedDepth      запрошенная глубина
 * @param totalClassesAnalyzed общее количество проанализированных классов
 */
public record ContextResponse(
        MergeRequestInfo mergeRequest,
        List<ClassStructure> classes,
        int requestedDepth,
        int totalClassesAnalyzed
) {}
