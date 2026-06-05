package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ответ эндпоинта POST /api/structure/plantuml/object.
 *
 * @param mergeRequest         метаданные мёрж-реквеста
 * @param plantUml             текст диаграммы PlantUML (class diagram)
 * @param pretty               {@code true} — с отступами, {@code false} — компактный вывод
 * @param requestedDepth       запрошенная глубина контекста
 * @param totalClassesAnalyzed количество классов на диаграмме
 */
@Schema(description = "PlantUML class diagram по контексту сессии")
public record PlantUmlResponse(

        @Schema(description = "Метаданные MR")
        MergeRequestInfo mergeRequest,

        @Schema(description = "Текст диаграммы PlantUML (@startuml … @enduml)")
        String plantUml,

        @Schema(description = "Применено форматирование pretty из запроса")
        boolean pretty,

        @Schema(description = "Запрошенная depth", example = "2")
        int requestedDepth,

        @Schema(description = "Число классов на диаграмме", example = "47")
        int totalClassesAnalyzed
) {}
