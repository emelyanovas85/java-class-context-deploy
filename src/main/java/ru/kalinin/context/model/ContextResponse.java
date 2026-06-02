package ru.kalinin.context.model;

import java.util.List;

/**
 * Ответ эндпоинта POST /api/context.
 *
 * @param mergeRequest         метаданные мёрж-реквеста
 * @param files                контексты по {@code .java}-файлам (классы внутри файла
 *                             могут иметь разный {@code level}), сортировка по {@code level}
 *                             файла, затем по {@code path}
 * @param requestedDepth       запрошенная глубина
 * @param totalClassesAnalyzed общее количество проанализированных классов
 */
public record ContextResponse(
        MergeRequestInfo mergeRequest,
        List<FileContext> files,
        int requestedDepth,
        int totalClassesAnalyzed
) {}
