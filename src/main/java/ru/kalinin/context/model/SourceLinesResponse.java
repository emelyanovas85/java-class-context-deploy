package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Ответ с содержимым запрошенных строк.
 *
 * <p>Каждый элемент {@code snippets} — это строки одного диапазона
 * в формате {@code "19: public void foo() {\n20:     bar();"}.
 * Порядок элементов соответствует порядку элементов в {@code rows} запроса.
 */
@Schema(description = "Строки исходного кода")
public record SourceLinesResponse(

        @Schema(description = "Результаты по каждому классу")
        List<FileResult> files
) {

    /**
     * Результат для одного класса.
     *
     * @param filePath qualified name или путь к файлу
     * @param error    сообщение об ошибке ({@code null} при успехе)
     * @param snippets список сниппетов по каждому диапазону из {@code rows},
     *                 {@code null} при ошибке
     */
    @Schema(description = "Результат для одного класса")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileResult(

            @Schema(description = "Qualified name или путь к файлу")
            String filePath,

            @Schema(description = "Ошибка, если класс недоступен (иначе null)")
            String error,

            @Schema(
                    description = "Сниппеты по диапазонам в порядке rows. Каждый элемент —"
                            + " строки одного диапазона с префиксом номера строки."
                            + " null при ошибке.",
                    example = "[\"19: public void foo() {\\n20:     bar();\\n21: }\"]")
            List<String> snippets
    ) {

        /** Фабричный метод для случая ошибки. */
        public static FileResult ofError(String filePath, String error) {
            return new FileResult(filePath, error, null);
        }

        /** Фабричный метод для успешного результата. */
        public static FileResult ofSnippets(String filePath, List<String> snippets) {
            return new FileResult(filePath, null, snippets);
        }
    }
}
