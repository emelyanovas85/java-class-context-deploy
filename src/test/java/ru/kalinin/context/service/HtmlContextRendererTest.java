package ru.kalinin.context.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.kalinin.context.model.ContextRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlContextRendererTest {

    @Test
    void injectsSerializedContextIntoTemplate() {
        HtmlContextRenderer renderer = new HtmlContextRenderer(new ObjectMapper());
        ContextRequest request = new ContextRequest(
                "https://gitlab.example.com",
                "group/project",
                "glpat-test",
                42L,
                2);

        String html = renderer.renderShell(request);

        assertThat(html).doesNotContain("__CONTEXT_REQUEST__");
        assertThat(html).doesNotContain("__CONTEXT_CSS__");
        assertThat(html).doesNotContain("__CONTEXT_JS__");
        assertThat(html).contains("<script type=\"application/json\" id=\"ctx-request\">");
        assertThat(html).contains("\"mergeRequestIid\":42");
        assertThat(html).contains("ctx-loading");
        assertThat(html).contains("ctx-spinner");
        assertThat(html).contains("body class=\"is-loading\"");
        assertThat(html).contains("'/api/context'");
        assertThat(html).contains("<style>");
        assertThat(html).contains(".ctx-block");
        assertThat(html).contains("function collectAllQualifiedNames");
        assertThat(html).doesNotContain("href=\"/context-debug.css\"");
        assertThat(html).doesNotContain("src=\"/context-debug.js\"");
    }
}
