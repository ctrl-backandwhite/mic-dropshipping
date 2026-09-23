package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La forma de envío que elige el cliente se revalida antes de cobrar.
 *
 * <p>El código de canal llega desde el navegador, así que no se puede usar tal cual: bastaría con
 * mandar el de un canal más barato para pagar de menos, o el de uno postal para colarse fuera del
 * régimen con IVA prepagado y romper el DDP. Se acepta solo si está entre las opciones que el
 * transportista cotiza AHORA para ese pedido, y el precio que se cobra es el de esa opción, nunca el
 * que venga del cliente.
 */
class ShippingOptionChoiceTest {

    private static final List<ShippingOption> COTIZADAS = List.of(
            new ShippingOption("FZZXR", "Apparel line", 785, 5, 8),
            new ShippingOption("THPHR", "Global line", 821, 6, 10));

    private static ShippingQuote quote() {
        return new ShippingQuote(true, "ES", 785, "Standard Shipping", "Standard Shipping", 5, 8, "EU", COTIZADAS);
    }

    @Test
    @DisplayName("elegir una opción cotizada cobra su precio y guarda su canal")
    void laOpcionElegidaMandaSobreLaMasBarata() {
        ShippingOption elegida = ShippingOptionResolver.resolve(quote(), "THPHR");

        assertThat(elegida.code()).isEqualTo("THPHR");
        assertThat(elegida.amountUsdCents()).isEqualTo(821);
    }

    @Test
    @DisplayName("sin elección se cobra la más barata")
    void sinEleccionSeUsaLaMasBarata() {
        assertThat(ShippingOptionResolver.resolve(quote(), null).code()).isEqualTo("FZZXR");
        assertThat(ShippingOptionResolver.resolve(quote(), "  ").code()).isEqualTo("FZZXR");
    }

    @Test
    @DisplayName("un canal que no cotiza se ignora: se cobra la más barata, no lo que pida el cliente")
    void unCanalInventadoNoSeAcepta() {
        // El caso que importa: mandar `CNDWA` —postal, más barato, sin IOSS— para pagar menos.
        ShippingOption elegida = ShippingOptionResolver.resolve(quote(), "CNDWA");

        assertThat(elegida.code()).isEqualTo("FZZXR");
        assertThat(elegida.amountUsdCents()).isEqualTo(785);
    }

    @Test
    @DisplayName("sin opciones cotizadas no hay elección posible")
    void sinOpcionesDevuelveNulo() {
        // Tarifa de la tabla de zonas: no hay canales entre los que elegir.
        ShippingQuote sinOpciones = new ShippingQuote(true, "ES", 750, "Standard Shipping", "Standard Shipping", 5, 12,
                "EU");

        assertThat(ShippingOptionResolver.resolve(sinOpciones, "THPHR")).isNull();
        assertThat(ShippingOptionResolver.resolve(sinOpciones, null)).isNull();
    }

    @Test
    @DisplayName("el código se compara sin distinguir mayúsculas ni espacios")
    void toleraEspaciosYMayusculas() {
        assertThat(ShippingOptionResolver.resolve(quote(), " thphr ").code()).isEqualTo("THPHR");
    }

    @Test
    @DisplayName("una cotización nula no revienta")
    void toleraLaAusenciaDeCotizacion() {
        assertThat(ShippingOptionResolver.resolve(null, "THPHR")).isNull();
    }
}
