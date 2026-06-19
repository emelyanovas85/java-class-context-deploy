package service.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Конфигурация HTTP-клиента ({@link RestClient}) к основному сервису.
 * Также явно регистрирует {@link ObjectMapper} из Jackson 3.x (tools.jackson),
 * который нужен SessionTools для сериализации ответа create_review_session.
 */
@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class RestClientConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    public RestClient upstreamRestClient(UpstreamProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory((ClientHttpRequestFactory) factory)
                .build();
    }
}
