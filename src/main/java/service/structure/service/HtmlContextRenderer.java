package service.structure.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import service.structure.model.ContextResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Собирает самодостаточную HTML-страницу отладки: шаблон + встроенные CSS, JS и JSON данных.
 */
@Component
public class HtmlContextRenderer {

    private static final String DATA_PLACEHOLDER = "__CONTEXT_DATA__";
    private static final String CSS_PLACEHOLDER = "__CONTEXT_CSS__";
    private static final String JS_PLACEHOLDER = "__CONTEXT_JS__";

    private static final String TEMPLATE_PATH = "templates/context-debug.html";
    private static final String CSS_PATH = "static/context-debug.css";
    private static final String JS_PATH = "static/context-debug.js";

    private final ObjectMapper objectMapper;
    private final String pageShell;

    public HtmlContextRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.pageShell = loadResource(TEMPLATE_PATH)
                .replace(CSS_PLACEHOLDER, loadResource(CSS_PATH))
                .replace(JS_PLACEHOLDER, loadResource(JS_PATH));
    }

    /** HTML со встроенным {@link ContextResponse} (без отдельного fetch на клиенте). */
    public String render(ContextResponse response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize context for debug page", e);
        }
        return pageShell.replace(DATA_PLACEHOLDER, escapeForScriptTag(json));
    }

    /** Не даёт закрыть {@code </script>} внутри JSON. */
    private static String escapeForScriptTag(String json) {
        return json.replace("</", "<\\/");
    }

    private static String loadResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load classpath resource: " + path, e);
        }
    }
}
