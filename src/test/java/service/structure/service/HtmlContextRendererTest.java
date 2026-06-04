package service.structure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import service.structure.model.ContextResponse;
import service.structure.model.FileContext;
import service.structure.model.UnchangedClassContext;

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

        assertThat(html).doesNotContain("__CONTEXT_DATA__");
        assertThat(html).doesNotContain("__CONTEXT_CSS__");
        assertThat(html).doesNotContain("__CONTEXT_JS__");
        assertThat(html).contains("<script type=\"application/json\" id=\"ctx-data\">");
        assertThat(html).contains("\"kind\":\"unchanged\"");
        assertThat(html).contains("com.example.Foo");
        assertThat(html).contains("ctx-loading");
        assertThat(html).contains("ctx-spinner");
        assertThat(html).contains("body class=\"is-loading\"");
        assertThat(html).contains("<style>");
        assertThat(html).contains(".ctx-block");
        assertThat(html).contains("function collectAllQualifiedNames");
        assertThat(html).doesNotContain("href=\"/context-debug.css\"");
        assertThat(html).doesNotContain("src=\"/context-debug.js\"");
    }
}
