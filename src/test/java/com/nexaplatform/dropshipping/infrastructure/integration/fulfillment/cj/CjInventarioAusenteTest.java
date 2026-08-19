package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cuántas veces se le pregunta a CJ por un SKU que todavía no ha recibido.
 *
 * <p>Un pedido cuya mercancía aún no ha llegado al almacén de CJ se queda esperando, y el planificador de
 * envíos lo reintenta <b>cada minuto</b>. Sin memoria de los ausentes, cada uno de esos intentos gasta una
 * llamada por cada SKU del pedido contra una API que admite <b>una petición por segundo</b> —la misma que
 * usa el checkout para cotizar, que sí está delante del cliente—. Con la mercancía tardando días en
 * llegar, eso es una petición por minuto y por SKU durante días.
 *
 * <p>La memoria de los ausentes caduca a propósito: el SKU aparecerá en cuanto CJ reciba el lote, así que
 * recordar «no está» para siempre dejaría el pedido esperando eternamente.
 */
class CjInventarioAusenteTest {

    private static final String SKU = "840171347183-2";

    private final AtomicInteger consultas = new AtomicInteger();
    private final RelojDePrueba reloj = new RelojDePrueba();

    @Test
    void noVuelveAPreguntarPorUnSkuAusenteDentroDeLaMismaVentana() {
        CjInventoryLookup inventario = inventarioSinNingunSku();

        for (int intento = 0; intento < 5; intento++) {
            assertThat(inventario.variantIdDe(SKU)).isEmpty();
        }

        assertThat(consultas.get()).isEqualTo(1);
    }

    @Test
    void vuelveAPreguntarCuandoCaducaLaVentana() {
        // Si no caducara, un lote recibido después no se vería nunca y el pedido no saldría jamás.
        CjInventoryLookup inventario = inventarioSinNingunSku();
        inventario.variantIdDe(SKU);

        reloj.avanza(Duration.ofMinutes(CjInventoryLookup.MINUTOS_DE_MEMORIA + 1L));
        inventario.variantIdDe(SKU);

        assertThat(consultas.get()).isEqualTo(2);
    }

    @Test
    void unSkuQueSiEstaSeSigueRecordandoSinCaducar() {
        // El identificador de una variante no cambia: una vez resuelto, no hay que volver a preguntarlo.
        CjInventoryLookup inventario = inventarioCon(SKU, "vid-1");
        inventario.variantIdDe(SKU);

        reloj.avanza(Duration.ofDays(2));

        assertThat(inventario.variantIdDe(SKU)).contains("vid-1");
        assertThat(consultas.get()).isEqualTo(1);
    }

    private CjInventoryLookup inventarioSinNingunSku() {
        return inventarioCon(null, null);
    }

    private CjInventoryLookup inventarioCon(String skuRecibido, String variantId) {
        return new CjInventoryLookup(sku -> {
            consultas.incrementAndGet();
            return sku.equals(skuRecibido)
                    ? """
                      {"code":200,"result":true,"data":{"list":[{"sku":"%s","variantId":"%s"}]}}
                      """.formatted(skuRecibido, variantId)
                    : """
                      {"code":200,"result":true,"data":{"list":[]}}
                      """;
        }, reloj);
    }

    /** Un reloj que solo avanza cuando la prueba lo dice: en esta batería no se espera por tiempo. */
    private static final class RelojDePrueba extends Clock {

        private Instant ahora = Instant.parse("2026-08-19T10:00:00Z");

        void avanza(Duration cuanto) {
            ahora = ahora.plus(cuanto);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }
}
