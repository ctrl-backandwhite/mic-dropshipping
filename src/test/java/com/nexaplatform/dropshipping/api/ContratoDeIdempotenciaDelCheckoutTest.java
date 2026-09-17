package com.nexaplatform.dropshipping.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El checkout exige la clave de idempotencia; no es opcional.
 *
 * <p>Siendo opcional, un cliente que se olvidara de mandarla obtenía un pedido NUEVO en cada POST: un
 * doble clic en «Pagar con saldo» —o el reintento del navegador tras un tiempo de espera— eran dos
 * pedidos, dos débitos del monedero y dos compras planificadas al proveedor, sin necesidad de
 * concurrencia. Y eso es exactamente lo que pasaba: la app móvil la mandaba y el escaparate web no.
 *
 * <p>Derivarla en el servidor a partir del carrito no sirve: no distingue un doble clic de volver a
 * comprar lo mismo más tarde, que es una compra legítima. La clave tiene que venir del cliente, que es
 * el único que sabe si esto es un reintento o una compra nueva. Que falte es un 400, no un cobro doble.
 */
@org.junit.jupiter.api.Disabled("PASO 1 DE 3 DEL DESPLIEGUE A PRODUCCIÓN — se reactiva en el paso 3. "
        + "La clave está OPCIONAL mientras el escaparate de producción, que todavía no la manda, convive "
        + "con este backend. Exigirla antes devolvería 400 en todo pago y toda recarga. En `develop` esta "
        + "prueba sigue activa y en verde: aquí solo se silencia durante la ventana del despliegue.")
class ContratoDeIdempotenciaDelCheckoutTest {

    /**
     * Los cuatro endpoints de {@code /api/me/**} que mueven dinero. Los de socios quedan fuera a
     * propósito: son contrato con terceros y no se les puede endurecer sin avisar.
     */
    private static Stream<Arguments> endpointsQueMuevenDinero() {
        return Stream.of(Arguments.of(MeOrderApi.class, "checkout"),
                Arguments.of(MeOrderPaymentApi.class, "initiate"),
                Arguments.of(MeOrderPaymentApi.class, "paySavedCard"),
                Arguments.of(MeWalletApi.class, "recharge"));
    }

    @ParameterizedTest(name = "{0}.{1} exige la clave de idempotencia")
    @MethodSource("endpointsQueMuevenDinero")
    void ningunEndpointDeDineroAceptaUnaPeticionSinClaveDeIdempotencia(Class<?> api, String metodo) {
        Method encontrado = null;
        for (Method m : api.getDeclaredMethods()) {
            if (metodo.equals(m.getName())) {
                encontrado = m;
            }
        }
        assertThat(encontrado).as("%s.%s debe existir", api.getSimpleName(), metodo).isNotNull();

        RequestHeader cabecera = null;
        for (Parameter p : encontrado.getParameters()) {
            RequestHeader anotacion = p.getAnnotation(RequestHeader.class);
            if (anotacion != null && "Idempotency-Key".equals(anotacion.value())) {
                cabecera = anotacion;
            }
        }
        assertThat(cabecera).as("%s.%s debe declarar la cabecera", api.getSimpleName(), metodo).isNotNull();
        assertThat(cabecera.required())
                .as("sin clave obligatoria, un reintento del cliente vuelve a cobrar")
                .isTrue();
    }

    @Test
    @DisplayName("el checkout no acepta una petición sin clave de idempotencia")
    void elCheckoutExigeLaClaveDeIdempotencia() throws NoSuchMethodException {
        Method checkout = null;
        for (Method m : MeOrderApi.class.getDeclaredMethods()) {
            if ("checkout".equals(m.getName())) {
                checkout = m;
            }
        }
        assertThat(checkout).as("MeOrderApi.checkout debe existir").isNotNull();

        RequestHeader cabecera = null;
        for (Parameter p : checkout.getParameters()) {
            RequestHeader anotacion = p.getAnnotation(RequestHeader.class);
            if (anotacion != null && "Idempotency-Key".equals(anotacion.value())) {
                cabecera = anotacion;
            }
        }
        assertThat(cabecera).as("el checkout debe declarar la cabecera Idempotency-Key").isNotNull();
        assertThat(cabecera.required())
                .as("sin clave obligatoria, un reintento del cliente crea un segundo pedido y lo vuelve a cobrar")
                .isTrue();
    }
}
