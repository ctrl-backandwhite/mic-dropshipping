package com.nexaplatform.dropshipping.infrastructure.integration.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Esquema del índice de productos: analizadores por idioma y mapping de campos.
 *
 * <p><b>Por qué existe.</b> El índice original se creaba con el analizador {@code standard} para todos los
 * títulos, así que la búsqueda multilingüe fallaba de dos formas simétricas: <em>no encontraba</em> lo que
 * debía (buscar "cana" no casaba "caña", "botas" no casaba "bota") y <em>encontraba de más</em> (sin
 * segmentación real del chino). Aquí cada idioma recibe su analizador — minúsculas, folding de acentos,
 * stopwords y stemmer propios — y el chino el analizador {@code cjk} (bigramas), que es el que de verdad
 * segmenta un texto sin espacios. La definición vive en {@code resources/opensearch/products-index.json}
 * porque un mapping con 8 idiomas es mucho más legible como JSON que como DSL encadenado.
 *
 * <p><b>Versionado.</b> El mapping de un índice NO se puede cambiar en caliente: añadir un analizador a un
 * campo existente exige un índice nuevo. Por eso el nombre físico lleva la versión del esquema
 * ({@code products-v2}). Al subir {@link #VERSION} se crea un índice nuevo, vacío, y el arranque lanza el
 * reindexado; mientras tanto la búsqueda cae al fallback SQL, que nunca depende de OpenSearch. El índice de
 * la versión anterior se queda ahí a propósito: no se borra nada automáticamente, se retira a mano cuando
 * la nueva versión está verificada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexSchema {

    /** Versión del esquema. Subirla ⇒ índice nuevo + reindexado (ver arriba). */
    public static final String VERSION = "v2";

    private static final String DEFINITION = "opensearch/products-index.json";

    private final OpenSearchClient client;

    /** Nombre físico versionado a partir del nombre lógico configurado ({@code products} → {@code products-v2}). */
    public String indexName(String logicalName) {
        return logicalName + "-" + VERSION;
    }

    /**
     * Crea el índice con sus analizadores si todavía no existe.
     *
     * @return {@code true} si lo acaba de crear (⇒ está vacío y hay que reindexar), {@code false} si ya estaba.
     * @throws IOException si OpenSearch no responde — lo trata quien llama; un buscador caído degrada la
     *                     búsqueda al fallback SQL, nunca impide arrancar.
     */
    public boolean createIfMissing(String logicalName) throws IOException {
        String index = indexName(logicalName);
        if (exists(index)) {
            return false;
        }
        String body = new ClassPathResource(DEFINITION).getContentAsString(StandardCharsets.UTF_8);
        Request request = Requests.builder().endpoint("/" + index).method("PUT").json(body).build();
        try (Response response = client.generic().execute(request)) {
            if (response.getStatus() >= 300) {
                String detail = response.getBody().map(Body::bodyAsString).orElse("(sin cuerpo)");
                log.error("No se pudo crear el índice '{}' ({}): {}", index, response.getStatus(), detail);
                return false;
            }
        }
        log.info("::> [SEARCH] índice '{}' creado con analizadores multilingües — pendiente de reindexar", index);
        return true;
    }

    /** ¿Existe ya el índice? Se pregunta con HEAD para no traerse el mapping entero. */
    private boolean exists(String index) throws IOException {
        try (Response response = client.generic()
                .execute(Requests.builder().endpoint("/" + index).method("HEAD").build())) {
            return response.getStatus() == 200;
        }
    }
}
