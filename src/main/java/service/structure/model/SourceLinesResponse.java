package service.structure.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Ответ с содержимым запрошенных строк.
 *
 * <p>Строки всех диапазонов одного класса объединяются в один список без дублей,
 * в порядке появления диапазонов в запросе. Дедупликация — по номеру строки.
 * Каждая строка имеет префикс номера: {@code "19: public void foo() {")}.
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
     * @param snippets строки всех запрошенных диапазонов, дедуплицированные по номеру строки.
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
                    description = "Строки всех диапазонов, дедуплицированные по номеру строки. "
                            + "Каждая строка — \"N: <content>\". null при ошибке.",
                    example = "[\"28: public class T6546\", \"29:     private static Документы окно\", \"40: public void t6546(...)\"]"
            )
            List<String> snippets
    ) {

        public static FileResult ofError(String filePath, String error) {
            return new FileResult(filePath, error, null);
        }

        public static FileResult ofSnippets(String filePath, List<String> snippets) {
            return new FileResult(filePath, null, snippets);
        }
    }
}
