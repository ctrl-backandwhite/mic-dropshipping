package com.nexaplatform.dropshipping.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conversión del mensaje de {@code product.ingested} al evento.
 *
 * <p>Por qué existe: el consumidor está configurado para deserializar SIEMPRE a un mapa
 * ({@code spring.json.use.type.headers: false} + {@code value.default.type: java.util.HashMap}), así
 * que los dos listeners que escuchan este tema —el indexador de búsqueda y el traductor— declaraban
 * {@link ProductIngestedEvent} en su firma y NUNCA llegaban a ejecutarse: Spring no sabía convertir el
 * mapa y el listener fallaba con cada mensaje.
 *
 * <p>El daño no era que faltara un producto en el buscador. Era el bucle: el 3-sep-2026 esto llenaba
 * el registro de preproducción y producción a 716 líneas de traza por hilo cada veinte minutos,
 * quemando procesador desde hacía días sin que nadie lo notara, porque nada de eso se veía en la
 * aplicación.
 */
class ProductIngestedEventTest {

    private static final UUID ID = UUID.fromString("3a2c20d8-2c6a-4823-baae-c1d711a78c7e");

    /** El mapa tal cual llega de Kafka: es el mensaje real que aparecía en la traza del fallo. */
    @Test
    void convierteElMensajeQueLlegaDeVerdad() {
        Map<String, Object> mensaje = new HashMap<>();
        mensaje.put("productId", "3a2c20d8-2c6a-4823-baae-c1d711a78c7e");
        mensaje.put("externalId", "777094690709");
        mensaje.put("source", "1688");
        mensaje.put("slug", "2025-777094690709");

        ProductIngestedEvent e = ProductIngestedEvent.desde(mensaje);

        assertThat(e).isNotNull();
        assertThat(e.productId()).isEqualTo(ID);
        assertThat(e.externalId()).isEqualTo("777094690709");
        assertThat(e.source()).isEqualTo("1688");
        assertThat(e.slug()).isEqualTo("2025-777094690709");
    }

    /** Si algún día el deserializador entrega un UUID ya construido, tiene que valer igual. */
    @Test
    void aceptaElIdentificadorComoUuidYaConstruido() {
        ProductIngestedEvent e = ProductIngestedEvent.desde(Map.of("productId", ID));

        assertThat(e).isNotNull();
        assertThat(e.productId()).isEqualTo(ID);
    }

    /**
     * Un mensaje sin identificador utilizable se descarta devolviendo null, y el listener lo ignora.
     * Es deliberado: lanzar aquí devolvería el problema que se viene a arreglar —un mensaje ilegible
     * reintentándose sin fin—.
     */
    @Test
    void unMensajeSinIdentificadorNoRompeNadaYSeDescarta() {
        assertThat(ProductIngestedEvent.desde(Map.of("source", "1688"))).isNull();
    }

    @Test
    void unIdentificadorQueNoEsUnUuidSeDescarta() {
        assertThat(ProductIngestedEvent.desde(Map.of("productId", "esto-no-es-un-uuid"))).isNull();
    }

    @Test
    void unMensajeNuloSeDescarta() {
        assertThat(ProductIngestedEvent.desde(null)).isNull();
    }

    /** Los campos que no vienen se quedan a null; el identificador es el único imprescindible. */
    @Test
    void soloElIdentificadorEsImprescindible() {
        ProductIngestedEvent e = ProductIngestedEvent.desde(Map.of("productId", ID.toString()));

        assertThat(e).isNotNull();
        assertThat(e.productId()).isEqualTo(ID);
        assertThat(e.slug()).isNull();
        assertThat(e.source()).isNull();
        assertThat(e.externalId()).isNull();
    }
}
