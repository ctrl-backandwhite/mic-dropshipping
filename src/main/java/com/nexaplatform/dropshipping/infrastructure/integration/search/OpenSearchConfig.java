package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Slf4j
@Configuration
public class OpenSearchConfig {

    @Bean
    public OpenSearchClient openSearchClient(@Value("${nexadrop.opensearch.uris}") String uris,
            ObjectMapper objectMapper) {
        URI uri = URI.create(uris.split(",")[0].trim());
        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
                .builder(new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort() == -1 ? 9200 : uri.getPort()))
                .setMapper(new JacksonJsonpMapper(objectMapper)).build();
        log.info("OpenSearch client configured against {}", uri);
        return new OpenSearchClient(transport);
    }
}
