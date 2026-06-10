package service.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Конфигурация HTTP-клиента ({@link RestClient}) к основному сервису.
 */
@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class RestClientConfig {

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
