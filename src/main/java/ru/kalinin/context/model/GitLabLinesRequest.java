package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Запрос на получение строк из файлов GitLab-репозитория.
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

        @Schema(description = "Список файлов и нужных строк")
        @NotEmpty(message = "files must not be empty")
        @Valid
        List<FileLines> files
) {

    /**
     * Один файл с набором диапазонов.
     *
     * @param filePath путь в репозитории, например {@code src/main/java/com/example/Foo.java}
     * @param rows     диапазоны строк
     */
    @Schema(description = "Файл и запрашиваемые диапазоны")
    public record FileLines(

            @Schema(description = "Путь к файлу в репозитории",
                    example = "src/main/java/com/example/Foo.java")
            @NotBlank(message = "filePath must not be blank")
            String filePath,

            @Schema(description = "Диапазоны строк", example = "[\"17\", \"19-22\"]")
            @NotEmpty(message = "rows must not be empty")
            List<String> rows
    ) {}
}
