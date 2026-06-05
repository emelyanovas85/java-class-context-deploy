package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Ответ {@code POST /api/source-file}: совпадения по каждому запрошенному имени.
 */
@Schema(description = "Полные исходники: результат по каждому имени из запроса")
public record FileSourceResponse(

        @Schema(description = "Результаты в порядке names из запроса")
        List<NameResult> names
) {

    @Schema(description = "Все совпадения для одного имени")
    public record NameResult(

            @Schema(description = "Исходное имя из запроса", example = "UserService")
            String name,

            @Schema(description = "Найденные файлы (может быть пустым)")
            List<FileMatch> files
    ) {}

    @Schema(description = "Один найденный исходник")
    public record FileMatch(

            @Schema(description = "repo — файл в GitLab; dependency — sources.jar", example = "repo")
            String origin,

            @Schema(description = "Путь в репозитории или synthetic path в jar", example = "src/main/java/com/example/UserService.java")
            String path,

            @Schema(description = "Qualified name класса", example = "com.example.UserService")
            String qualifiedName,

            @Schema(description = "src/main, src/test или groupId:artifactId:version", example = "src/main")
            String module,

            @Schema(description = "Полный текст .java файла")
            String content
    ) {}
}
