package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.net.URI;

@Slf4j
@Configuration
public class OpenSearchConfig {

    /**
     * Cliente OpenSearch.
     *
     * <ul>
     *   <li><b>Local</b> (http, sin credenciales): conexión plana, igual que antes.</li>
     *   <li><b>Remoto seguro</b> (https + usuario/clave): el plugin de seguridad de OpenSearch
     *       exige <b>auth básica</b> y sirve por <b>TLS</b>. Se añade el credentials provider y,
     *       para el certificado autofirmado del despliegue (red privada), un TLS que confía en él
     *       y omite la verificación de hostname. El tráfico va cifrado y autenticado; al estar en
     *       red privada sin dominio público, el riesgo de MITM es mínimo. Para verificación
     *       estricta de cert habría que importar la CA de OpenSearch.</li>
     * </ul>
     */
    @Bean
    public OpenSearchClient openSearchClient(@Value("${nexadrop.opensearch.uris}") String uris,
            @Value("${nexadrop.opensearch.username:}") String username,
            @Value("${nexadrop.opensearch.password:}") String password,
            @Value("${nexadrop.opensearch.tls-insecure:false}") boolean tlsInsecure, ObjectMapper objectMapper) {
        URI uri = URI.create(uris.split(",")[0].trim());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean withAuth = username != null && !username.isBlank();

        // Nunca enviar auth básica en claro: si hay credenciales, exige https (fail-closed).
        if (withAuth && !https) {
            throw new IllegalStateException(
                    "OpenSearch con credenciales pero URI no es https — me niego a enviar Basic auth en claro. "
                            + "Usa https en nexadrop.opensearch.uris.");
        }

        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort() == -1 ? 9200 : uri.getPort());
        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper(objectMapper));

        // TLS relajado (confía en cert autofirmado, sin verificar hostname) SOLO si se opta
        // explícitamente con nexadrop.opensearch.tls-insecure=true. Por defecto: TLS estricto
        // (truststore del sistema). Pensado para el cert autofirmado del OpenSearch interno.
        boolean useInsecureTls = https && tlsInsecure;
        if (https && tlsInsecure) {
            log.warn("OpenSearch TLS en modo INSEGURO (trust-all): solo válido para el cert autofirmado "
                    + "del servicio interno en red privada. No usar contra un OpenSearch público.");
        }

        if (withAuth || useInsecureTls) {
            BasicCredentialsProvider creds = new BasicCredentialsProvider();
            if (withAuth) {
                creds.setCredentials(new AuthScope(host),
                        new UsernamePasswordCredentials(username, password.toCharArray()));
            }
            TlsStrategy tlsStrategy = useInsecureTls ? buildTrustAllTls() : null;
            builder.setHttpClientConfigCallback(http -> {
                if (withAuth) {
                    http.setDefaultCredentialsProvider(creds);
                }
                if (tlsStrategy != null) {
                    http.setConnectionManager(
                            PoolingAsyncClientConnectionManagerBuilder.create().setTlsStrategy(tlsStrategy).build());
                }
                return http;
            });
        }

        OpenSearchTransport transport = builder.build();
        log.info("OpenSearch client configured against {} (tls={}, auth={}, insecureTls={})", uri, https, withAuth,
                useInsecureTls);
        return new OpenSearchClient(transport);
    }

    /** TLS que confía en el certificado autofirmado del OpenSearch interno (red privada). */
    private static TlsStrategy buildTrustAllTls() {
        try {
            SSLContext sslContext = SSLContextBuilder.create().loadTrustMaterial(null, TrustAllStrategy.INSTANCE)
                    .build();
            return ClientTlsStrategyBuilder.create().setSslContext(sslContext)
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE).build();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo construir el TLS para OpenSearch", e);
        }
    }
}
