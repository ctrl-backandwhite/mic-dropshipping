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

    /**
     * Versión del esquema. Subirla ⇒ índice nuevo + reindexado (ver arriba).
     *
     * <p>v4: singular y plural dejaban de encontrar lo mismo. Dos causas, ambas medidas sobre el catálogo:
     * <ul>
     *   <li><b>{@code nx_foreign} es un {@code stemmer_override}</b>, y un override marca el token como YA
     *       PROCESADO: el stemmer posterior no vuelve a tocarlo. Las reglas estaban escritas como
     *       «blazers ⇒ blazer», de modo que el plural se quedaba en {@code blazer} mientras el singular se
     *       reducía a {@code blaz} — buscar «blazer» NO encontraba los «blazers» (265 resultados frente a
     *       532). Ahora cada regla apunta a la RAÍZ del singular. Y las palabras que el stemmer español ya
     *       unifica solo (polo, pijama, hoodie) se RETIRAN del override, donde únicamente estorbaban:
     *       «pijama» devolvía 73 resultados y «pijamas», 1.</li>
     *   <li><b>Los sinónimos estaban solo en plural</b>: «zapatillas» encontraba sneakers y tenis, y
     *       «zapatilla» no encontraba ninguno. Se añaden las formas en singular.</li>
     * </ul>
     *
     * <p>v3: {@code titleAll} pasa del analizador {@code nx_plain} al nuevo {@code nx_all}, que sí filtra
     * palabras vacías. Antes no lo hacía, y como ese campo reúne los siete idiomas, cada "de", "con",
     * "with" o "mit" de la consulta contaba como término propio: buscar «de traje de» casaba con 5.247
     * productos por ese solo campo, frente a los 77 del campo del idioma. El resultado correcto seguía
     * saliendo, pero enterrado.
     */
    public static final String VERSION = "v4";

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
