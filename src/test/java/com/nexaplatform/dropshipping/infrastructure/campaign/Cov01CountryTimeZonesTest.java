package com.nexaplatform.dropshipping.infrastructure.campaign;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas del reparto horario de campañas: de qué zona es cada país y quién entra en la franja de las
 * 18:00 / 14:00 locales.
 */
class Cov01CountryTimeZonesTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Test
    void paisSinMapearCaeEnLaZonaPorDefectoYNoSeQuedaFueraDeLasCampanas() {
        // Si un país desconocido devolviera null, la campaña reventaría al calcular su hora local y ese
        // usuario no recibiría jamás el correo.
        assertThat(CountryTimeZones.zoneOf("ZZ")).isEqualTo(MADRID);
        assertThat(CountryTimeZones.zoneOf(null)).isEqualTo(MADRID);
        assertThat(CountryTimeZones.zoneOf("")).isEqualTo(MADRID);
    }

    @Test
    void elCodigoDePaisSeNormalizaEnMinusculasYConEspacios() {
        // El código llega de la dirección del usuario, tecleada a mano: "  us  " es Estados Unidos.
        assertThat(CountryTimeZones.zoneOf("  us  ")).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(CountryTimeZones.zoneOf("Mx")).isEqualTo(ZoneId.of("America/Mexico_City"));
    }

    @Test
    void cadaPaisMapeadoTieneSuZonaRepresentativaYNoLaPorDefecto() {
        assertThat(CountryTimeZones.zoneOf("ES")).isEqualTo(MADRID);
        assertThat(CountryTimeZones.zoneOf("BR")).isEqualTo(ZoneId.of("America/Sao_Paulo"));
        assertThat(CountryTimeZones.zoneOf("AR")).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
        assertThat(CountryTimeZones.zoneOf("GB")).isEqualTo(ZoneId.of("Europe/London"));
    }

    @Test
    void elMapaDePaisesEsInmutableParaQueNadieLoCorrompaEnCaliente() {
        // Es estático y compartido por todas las campañas: una modificación accidental afectaría a todos
        // los envíos posteriores del proceso.
        Set<String> countries = CountryTimeZones.allCountries();
        assertThatThrownBy(() -> countries.add("XX")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void todosLosPaisesEstanEnAlgunaFranjaHorariaYEnUnaSola() {
        // Reparto exhaustivo: recorriendo las 24 horas se cubre el censo entero, sin dejar países fuera
        // (nunca recibirían campaña) ni contarlos dos veces (recibirían el correo duplicado).
        Set<String> union = new HashSet<>();
        int repeated = 0;
        for (int hour = 0; hour < 24; hour++) {
            for (String country : CountryTimeZones.countriesAtLocalHour(hour)) {
                if (!union.add(country)) {
                    repeated++;
                }
            }
        }
        assertThat(union).isEqualTo(CountryTimeZones.allCountries());
        assertThat(repeated).isZero();
    }

    @Test
    void unaHoraInexistenteNoDevuelveNingunPais() {
        assertThat(CountryTimeZones.countriesAtLocalHour(-1)).isEmpty();
        assertThat(CountryTimeZones.countriesAtLocalHour(24)).isEmpty();
    }

    @Test
    void losPaisesDeUnaFranjaConfirmanEsaMismaHoraLocal() {
        int hour = ZonedDateTime.now(MADRID).getHour();
        Set<String> inSlot = CountryTimeZones.countriesAtLocalHour(hour);

        // La franja de Madrid siempre tiene países: sin esta comprobación, allSatisfy pasaría también con
        // el conjunto vacío y el test quedaría en verde sin haber comprobado nada.
        assertThat(inSlot).isNotEmpty()
                .allSatisfy(country -> assertThat(CountryTimeZones.isLocalHour(country, hour)).isTrue());
    }

    @Test
    void laHoraLocalSeCalculaEnLaZonaDelPaisNoEnLaDelServidor() {
        LocalTime madrid = CountryTimeZones.localTime("ES");
        LocalTime mexico = CountryTimeZones.localTime("MX");

        assertThat(madrid).isNotNull();
        assertThat(mexico).isNotNull();
        // Madrid y Ciudad de México nunca comparten hora del reloj: si coincidieran, se estaría usando la
        // hora del servidor para todos y las campañas llegarían de madrugada a media plantilla.
        assertThat(madrid.getHour()).isNotEqualTo(mexico.getHour());
    }

    @Test
    void unPaisDesconocidoSigueLaFranjaDeLaZonaPorDefecto() {
        int madridHour = ZonedDateTime.now(MADRID).getHour();

        assertThat(CountryTimeZones.isLocalHour("ZZ", madridHour))
                .isEqualTo(CountryTimeZones.isLocalHour("ES", madridHour));
    }
}
