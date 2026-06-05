package service.structure.service;

import service.structure.model.ContextResponse;
import service.structure.model.FileContext;

import java.util.List;

/** Форматирование {@link ContextResponse} для {@code POST /api/structure/markdown}. */
public final class ContextMarkdownFormatter {

    private ContextMarkdownFormatter() {}

    /** По одному {@link FileContext#toString()} на каждый файл в порядке ответа. */
    public static List<String> toMarkdownLines(ContextResponse response) {
        return response.files().stream()
                .map(FileContext::toString)
                .toList();
    }
}
