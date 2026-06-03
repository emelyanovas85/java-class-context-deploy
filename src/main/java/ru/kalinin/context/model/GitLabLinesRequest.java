package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Запрос на получение строк из файлов GitLab-репозитория.
 *
 * <p>Файл идентифицируется по {@code qualifiedName} класса — сервер
 * сам определяет путь через файловый индекс (TTL-кэш 15 минут).
 * Поле {@code module} уточняет поиск и устраняет коллизии между
 * одноимёнными классами в main/test.
 *
 * <p>Формат диапазона строк — тот же, что в {@link StructureNode#rows()}:
 * {@code "17"} (одна строка) или {@code "19-22"} (включительно).
 */
@Schema(description = "Параметры запроса строк из GitLab")
public record GitLabLinesRequest(

        @Schema(description = "URL GitLab-инстанса", example = "https://gitlab.com")
        @NotBlank(message = "gitlabUrl must not be blank")
        String gitlabUrl,

        @Schema(description = "ID проекта или namespace/name", example = "mygroup/myproject")
        @NotBlank(message = "projectId must not be blank")
        String projectId,

        @Schema(description = "Personal или Project Access Token")
        @NotBlank(message = "token must not be blank")
        String token,

        @Schema(description = "Ветка или коммит (ref)", example = "main")
        @NotBlank(message = "ref must not be blank")
        String ref,

        @Schema(description = "Список классов и нужных строк")
        @NotEmpty(message = "classes must not be empty")
        @Valid
        List<ClassLines> classes
) {

    /**
     * Один класс с набором диапазонов.
     *
     * @param qualifiedName полное имя класса, например {@code simpleTest.credit.T6546}
     * @param source        источник из {@link ClassContext#module()}:
     *                      {@code "main"}, {@code "test"} или {@code null}.
     *                      Используется для разрешения коллизий
     *                      между одноимёнными классами в main и test
     * @param rows          диапазоны строк
     */
    @Schema(description = "Класс и запрашиваемые диапазоны")
    public record ClassLines(

            @Schema(description = "Qualified name класса",
                    example = "simpleTest.credit.T6546")
            @NotBlank(message = "qualifiedName must not be blank")
            String qualifiedName,

            @Schema(
                    description = "Источник из ClassContext.module(): \"main\" | \"test\" | null."
                            + " Уточняет поиск при коллизии одноимённых классов.",
                    example = "main",
                    nullable = true
            )
            String source,

            @Schema(description = "Диапазоны строк", example = "[\"28-168\"]")
            @NotEmpty(message = "rows must not be empty")
            List<String> rows
    ) {
        /** Префикс пути в репозитории для данного module. */
        public String sourcePathPrefix() {
            if ("test".equals(source)) return "src/test/java";
            if ("main".equals(source)) return "src/main/java";
            return null; // нет предпочтения
        }
    }
}
