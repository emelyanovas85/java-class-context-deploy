package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Запрос на получение строк из локального {@code *-sources.jar} внешней зависимости.
 *
 * <p>Формат диапазона строк — тот же, что в {@link StructureNode#rows()}:
 * {@code "17"} (одна строка) или {@code "19-22"} (включительно).
 */
@Schema(description = "Параметры запроса строк из sources.jar")
public record JarLinesRequest(

        @Schema(
                description = "Maven-координаты зависимости в формате groupId:artifactId:version."
                        + " Соответствует полю source в ClassContext.",
                example = "org.aspectj:aspectjweaver:1.9.22"
        )
        @NotBlank(message = "source must not be blank")
        @Pattern(
                regexp = "[^:]+:[^:]+:[^:]+",
                message = "source must be in format groupId:artifactId:version"
        )
        String source,

        @Schema(description = "Список классов и нужных строк")
        @NotEmpty(message = "classes must not be empty")
        @Valid
        List<ClassLines> classes
) {

    /**
     * Один класс с набором диапазонов.
     *
     * @param qualifiedName qualified name класса внутри jar
     * @param rows          диапазоны строк
     */
    @Schema(description = "Класс и запрашиваемые диапазоны")
    public record ClassLines(

            @Schema(description = "Qualified name класса",
                    example = "org.aspectj.weaver.Advice")
            @NotBlank(message = "qualifiedName must not be blank")
            String qualifiedName,

            @Schema(description = "Диапазоны строк", example = "[\"17\", \"19-22\"]")
            @NotEmpty(message = "rows must not be empty")
            List<String> rows
    ) {}
}
