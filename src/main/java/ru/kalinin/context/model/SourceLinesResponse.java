package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Ответ с содержимым запрошенных строк.
 */
@Schema(description = "Строки исходного кода из GitLab-файлов")
public record SourceLinesResponse(

        @Schema(description = "Результаты по каждому файлу")
        List<FileResult> files
) {

    /**
     * Результат для одного файла.
     *
     * @param filePath путь к файлу
     * @param error    сообщение об ошибке (если файл не найден или недоступен)
     * @param snippets найденные сниппеты, {@code null} при ошибке
     */
    @Schema(description = "Результат для одного файла")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileResult(

            @Schema(description = "Путь к файлу")
            String filePath,

            @Schema(description = "Ошибка, если файл недоступен (иначе null)")
            String error,

            @Schema(description = "Сниппеты по запрошенным диапазонам (null при ошибке)")
            List<Snippet> snippets
    ) {

        /** Фабричный метод для случая ошибки. */
        public static FileResult ofError(String filePath, String error) {
            return new FileResult(filePath, error, null);
        }

        /** Фабричный метод для успешного результата. */
        public static FileResult ofSnippets(String filePath, List<Snippet> snippets) {
            return new FileResult(filePath, null, snippets);
        }
    }

    /**
     * Один сниппет — строки одного диапазона.
     *
     * @param rows    запрошенный диапазон в оригинальном формате ({@code "17"} или {@code "19-22"})
     * @param content конкатенированные строки с префиксом номера строки
     */
    @Schema(description = "Строки одного диапазона")
    public record Snippet(

            @Schema(description = "Запрошенный диапазон", example = "19-22")
            String rows,

            @Schema(description = "Строки с номерами",
                    example = "19: public void foo() {\n20:     bar();\n21: }")
            String content
    ) {}
}
