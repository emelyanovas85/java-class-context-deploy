package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Ответ эндпоинта POST /api/structure/json.
 */
@Schema(description = "Структурный контекст MR: классы сгруппированы по файлам, source vs target")
public record ContextResponse(

        @Schema(description = "Метаданные MR и список изменённых файлов")
        MergeRequestInfo mergeRequest,

        @Schema(description = "Контексты по .java-файлам; внутри — ClassContext с level, callerIds, структурами")
        List<FileContext> files,

        @Schema(description = "Запрошенная глубина depth из тела запроса", example = "2")
        int requestedDepth,

        @Schema(description = "Общее число ClassContext в ответе", example = "47")
        int totalClassesAnalyzed
) {}
