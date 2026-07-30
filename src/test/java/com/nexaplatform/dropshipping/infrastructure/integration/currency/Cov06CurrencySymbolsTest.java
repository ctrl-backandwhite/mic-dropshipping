package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metadatos de presentación de divisas. La regla que protege al usuario es la de reserva: ante una
 * divisa desconocida se enseña su CÓDIGO, nunca un símbolo inventado que haría leer mal el importe.
 */
class Cov06CurrencySymbolsTest {

    @Test
    void devuelveElSimboloDeUnaDivisaConocida() {
        assertThat(CurrencySymbols.symbolFor("EUR")).isEqualTo("€");
        assertThat(CurrencySymbols.nameFor("EUR")).isEqualTo("Euro");
    }

    /** El código llega en cualquier caja (del proveedor de tasas o del header X-Currency): se normaliza. */
    @Test
    void elCodigoSeNormalizaAMayusculas() {
        assertThat(CurrencySymbols.symbolFor("usd")).isEqualTo("$");
        assertThat(CurrencySymbols.nameFor("usd")).isEqualTo("US Dollar");
    }

    /**
     * Divisa no mapeada: se devuelve el propio código. Inventar un símbolo (por ejemplo "$" para SEK)
     * haría que el usuario leyera un importe en una moneda que no es la suya.
     */
    @Test
    void divisaDesconocidaSeMuestraConSuPropioCodigo() {
        assertThat(CurrencySymbols.symbolFor("xof")).isEqualTo("XOF");
        assertThat(CurrencySymbols.nameFor("xof")).isEqualTo("XOF");
    }

    /** Sin código no hay nada que mostrar: el símbolo cae al dólar y el nombre queda vacío. */
    @Test
    void sinCodigoUsaLosValoresDeReserva() {
        assertThat(CurrencySymbols.symbolFor(null)).isEqualTo("$");
        assertThat(CurrencySymbols.nameFor(null)).isEmpty();
    }

    /** Las divisas que comparten glifo mantienen nombres distintos (el símbolo por sí solo es ambiguo). */
    @Test
    void divisasQueCompartenGlifoConservanNombrePropio() {
        assertThat(CurrencySymbols.symbolFor("CNY")).isEqualTo(CurrencySymbols.symbolFor("JPY"));
        assertThat(CurrencySymbols.nameFor("CNY")).isEqualTo("Chinese Yuan");
        assertThat(CurrencySymbols.nameFor("JPY")).isEqualTo("Japanese Yen");
    }

    /** Cadena vacía: no está mapeada, así que se devuelve tal cual (no es "sin código"). */
    @Test
    void codigoVacioSeDevuelveTalCual() {
        assertThat(CurrencySymbols.symbolFor("")).isEmpty();
        assertThat(CurrencySymbols.nameFor("")).isEmpty();
    }
}
