package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Класс и диапазоны строк для {@code /api/source-lines/*}.
 */
@Schema(description = "Класс и диапазоны строк для /api/source-lines/*")
public record ClassLines(

        @Schema(description = "Qualified name класса в файле", example = "com.example.Foo")
        @NotBlank(message = "qualifiedName must not be blank")
        String qualifiedName,

        @Schema(description = "Для GitLab: main или test — disambiguation при коллизии имён. Для jar: не используется", example = "main", nullable = true)
        String source,

        @Schema(description = "Диапазоны: одна строка \"17\" или включительный \"19-22\"", example = "[\"28-168\"]")
        @NotEmpty(message = "rows must not be empty")
        List<String> rows
) {
    /** Префикс пути в репозитории для разрешения коллизий main/test. */
    public String sourcePathPrefix() {
        if ("test".equals(source)) return "src/test/java";
        if ("main".equals(source)) return "src/main/java";
        return null;
    }
}
