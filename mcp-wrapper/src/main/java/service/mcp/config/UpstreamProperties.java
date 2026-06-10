package service.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки подключения к основному сервису Java Class Context API.
 *
 * @param baseUrl         базовый URL основного сервиса (например, http://10.1.5.97:8084)
 * @param connectTimeout  таймаут установления соединения, мс
 * @param readTimeout     таймаут чтения ответа, мс (build merged index может занимать время)
 */
@ConfigurationProperties(prefix = "app.upstream")
public record UpstreamProperties(
        String baseUrl,
        int connectTimeout,
        int readTimeout
) {
    public UpstreamProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://10.1.5.97:8084";
        }
        if (connectTimeout <= 0) {
            connectTimeout = 5_000;
        }
        if (readTimeout <= 0) {
            readTimeout = 120_000;
        }
    }
}
