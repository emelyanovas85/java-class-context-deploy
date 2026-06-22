package service.mcp.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import service.mcp.tools.SessionTools;
import service.mcp.tools.SourceTools;
import service.mcp.tools.StructureTools;

/**
 * Регистрация MCP-инструментов в отдельном @Configuration-классе.
 *
 * <p>Вынесено из McpWrapperApplication намеренно: бин ToolCallbackProvider
 * нельзя объявлять в @SpringBootApplication-классе, так как он создаётся
 * в фазе BeanPostProcessor и мешает автоконфигурации MCP-сервера
 * (McpServerAnnotationScannerAutoConfiguration) корректно зарегистрировать
 * HTTP-эндпоинты /mcp и /sse.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider javaClassContextTools(SessionTools sessionTools,
                                                      StructureTools structureTools,
                                                      SourceTools sourceTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(sessionTools, structureTools, sourceTools)
                .build();
    }
}
