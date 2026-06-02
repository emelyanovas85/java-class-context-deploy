package ru.kalinin.context.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.ContextResponse;
import ru.kalinin.context.model.FileContext;
import ru.kalinin.context.model.UnchangedClassContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlContextRendererTest {

    @Test
    void injectsSerializedContextIntoTemplate() {
        HtmlContextRenderer renderer = new HtmlContextRenderer(new ObjectMapper());
        ContextResponse response = new ContextResponse(
                null,
                List.of(new FileContext(
                        "src/main/java/com/example/Foo.java",
                        "main",
                        0,
                        List.of(new UnchangedClassContext(
                                1, "com.example.Foo", 0, Set.of(), "main", List.of())))),
                2,
                1);

        String html = renderer.render(response);

        assertThat(html).doesNotContain("__CONTEXT_JSON__");
        assertThat(html).doesNotContain("__CONTEXT_CSS__");
        assertThat(html).doesNotContain("__CONTEXT_JS__");
        assertThat(html).contains("<script type=\"application/json\" id=\"ctx-data\">");
        assertThat(html).contains("\"kind\":\"unchanged\"");
        assertThat(html).contains("com.example.Foo");
        assertThat(html).contains("<style>");
        assertThat(html).contains(".ctx-block");
        assertThat(html).contains("function collectAllQualifiedNames");
        assertThat(html).doesNotContain("href=\"/context-debug.css\"");
        assertThat(html).doesNotContain("src=\"/context-debug.js\"");
    }
}
