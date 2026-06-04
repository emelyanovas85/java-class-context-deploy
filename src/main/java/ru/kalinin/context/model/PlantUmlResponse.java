package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ответ эндпоинта POST /api/plantuml.
 *
 * @param mergeRequest         метаданные мёрж-реквеста
 * @param plantUml             текст диаграммы PlantUML (class diagram)
 * @param pretty               {@code true} — с отступами, {@code false} — компактный вывод
 * @param requestedDepth       запрошенная глубина контекста
 * @param totalClassesAnalyzed количество классов на диаграмме
 */
@Schema(description = "PlantUML class diagram, построенная по контексту MR")
public record PlantUmlResponse(
        MergeRequestInfo mergeRequest,
        String plantUml,
        boolean pretty,
        int requestedDepth,
        int totalClassesAnalyzed
) {}
