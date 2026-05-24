package ru.kalinin.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Ответ с содержимым запрошенных строк.
 *
 * <p>Каждый элемент {@code snippets} — список строк одного диапазона.
 * Каждая строка имеет префикс номера: {@code "19: public void foo() {")}.
 * Порядок элементов соответствует порядку элементов {@code rows} запроса.
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
     * @param snippets список диапазонов; каждый диапазон — список строк с префиксом номера.
     *                 {@code null} при ошибке.
     */
    @Schema(description = "Результат для одного класса")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileResult(

            @Schema(description = "Qualified name или путь к файлу")
            String filePath,

            @Schema(description = "Ошибка, если класс недоступен (иначе null)")
            String error,

            @Schema(
                    description = "Сниппеты по диапазонам в порядке rows. "
                            + "Каждый элемент — список строк одного диапазона с префиксом номера. "
                            + "null при ошибке.",
                    example = "[[\"28: public class T6546\", \"29:     private static Документы окно\"], "
                            + "[\"40: public void t6546(...)\"]]"
            )
            List<List<String>> snippets
    ) {

        public static FileResult ofError(String filePath, String error) {
            return new FileResult(filePath, error, null);
        }

        public static FileResult ofSnippets(String filePath, List<List<String>> snippets) {
            return new FileResult(filePath, null, snippets);
        }
    }
}
