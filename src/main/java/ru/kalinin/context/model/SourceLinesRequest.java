package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Запрос на получение строк исходного кода из GitLab или из локального sources.jar.
 *
 * <p>Для каждого файла передаётся произвольный набор диапазонов строк
 * в формате {@code "17"} или {@code "19-22"}
 * (тот же формат, что в {@link StructureNode#rows()}).
 *
 * <h3>Источник строк</h3>
 * <ul>
 *   <li>Поле {@link FileLines#source()} = {@code null} / пусто: читаем из GitLab
 *       (требуются {@code gitlabUrl}, {@code projectId}, {@code token}, {@code ref}).</li>
 *   <li>Поле {@link FileLines#source()} = {@code "groupId:artifactId:version"} (например
 *       {@code "org.aspectj:aspectjweaver:1.9.22"}): читаем из локального
 *       {@code sources.jar}, который должен быть скачан заранее.
 *       {@code filePath} в этом случае — qualified name класса,
 *       например {@code "org.aspectj.weaver.Advice"}.</li>
 * </ul>
 */
@Schema(description = "Параметры запроса строк исходного кода")
public record SourceLinesRequest(

        @Schema(description = "URL GitLab-инстанса (обязательно, если есть файлы без source)",
                example = "https://gitlab.com")
        String gitlabUrl,

        @Schema(description = "ID проекта или namespace/name", example = "mygroup/myproject")
        String projectId,

        @Schema(description = "Personal или Project Access Token")
        String token,

        @Schema(description = "Ветка или коммит (ref)", example = "main")
        String ref,

        @Schema(description = "Список файлов и нужных строк")
        @NotEmpty(message = "files must not be empty")
        @Valid
        List<FileLines> files
) {

    /**
     * Один файл с набором диапазонов строк.
     *
     * @param filePath путь в репозитории (GitLab) или qualified name класса (для jar)
     * @param source   null/пусто — GitLab; {@code "groupId:artifactId:version"} — jar
     * @param rows     список диапазонов в формате {@code "17"} или {@code "19-22"}
     */
    @Schema(description = "Файл и запрашиваемые диапазоны строк")
    public record FileLines(

            @Schema(
                    description = "Путь в репозитории (GitLab) или qualified name (jar)",
                    example = "src/main/java/com/example/Foo.java"
            )
            @NotBlank(message = "filePath must not be blank")
            String filePath,

            @Schema(
                    description = "Источник: null = GitLab, или groupId:artifactId:version = jar",
                    example = "org.aspectj:aspectjweaver:1.9.22",
                    nullable = true
            )
            String source,

            @Schema(description = "Диапазоны строк", example = "[\"17\", \"19-22\", \"55\"]")
            @NotEmpty(message = "rows must not be empty")
            List<String> rows
    ) {
        /** Возвращает true, если источник — локальный jar. */
        public boolean isJarSource() {
            return source != null && !source.isBlank()
                    && !"main".equals(source) && !"test".equals(source);
        }
    }
}
