package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Запрос на получение строк исходного кода из GitLab.
 *
 * <p>Передаётся список файлов, для каждого — произвольный набор
 * диапазонов строк в формате {@code "17"} или {@code "19-22"}
 * (тот же формат, что в {@link StructureNode#rows()}).
 */
@Schema(description = "Параметры запроса строк исходного кода")
public record SourceLinesRequest(

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

        @Schema(description = "Список файлов и нужных строк")
        @NotEmpty(message = "files must not be empty")
        @Valid
        List<FileLines> files
) {

    /**
     * Один файл с набором диапазонов строк.
     *
     * @param filePath путь в репозитории, например {@code src/main/java/com/example/Foo.java}
     * @param rows     список диапазонов в формате {@code "17"} или {@code "19-22"}
     */
    @Schema(description = "Файл и запрашиваемые диапазоны строк")
    public record FileLines(

            @Schema(description = "Путь к файлу в репозитории",
                    example = "src/main/java/com/example/Foo.java")
            @NotBlank(message = "filePath must not be blank")
            String filePath,

            @Schema(description = "Диапазоны строк", example = "[\"17\", \"19-22\", \"55\"]")
            @NotEmpty(message = "rows must not be empty")
            List<String> rows
    ) {}
}
