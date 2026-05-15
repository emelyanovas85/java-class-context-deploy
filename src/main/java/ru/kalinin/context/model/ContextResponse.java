package ru.kalinin.context.model;

import java.util.List;

/**
 * Ответ эндпоинта POST /api/context.
 *
 * @param mergeRequest        метаданные мёрж-реквеста
 * @param classes             контексты всех проанализированных классов,
 *                            сортированные по {@code level}
 * @param requestedDepth      запрошенная глубина
 * @param totalClassesAnalyzed общее количество проанализированных классов
 */
public record ContextResponse(
        MergeRequestInfo mergeRequest,
        List<ClassContext> classes,
        int requestedDepth,
        int totalClassesAnalyzed
) {}
