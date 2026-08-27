package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.UnserviceableZoneService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los destinos donde el transportista NO entrega, comprobados contra los rangos que siembra la
 * migración, no contra una lista escrita en el test.
 *
 * <p>Por qué hace falta un test de integración y no basta el unitario: lo que puede fallar aquí no es
 * la comparación de rangos —eso ya lo fija {@code UnserviceableZoneServiceTest}— sino los <b>datos</b>.
 * Los 635 rangos vienen de la lista {@code 不可到地区清单} de YunExpress y se cargaron a mano; un cero a la
 * izquierda de menos en un código postal austriaco, o un rango portugués escrito en el formato que no
 * es, deja pasar pedidos que luego no se pueden despachar. Este fichero comprueba ciudades reales
 * contra la base de datos con la migración aplicada.
 *
 * <p>Se prueban también las ciudades que SÍ se sirven: una regla demasiado ancha —bloquear «35» entero
 * en vez de «35000-35999»— impediría vender a media España sin que nadie se enterase hasta recibir la
 * queja.
 */
class UnserviceableZoneIT extends BaseIntegration {

    /** El fichero que de verdad se despliega. Ver {@link #reponerLaListaDelTransportista()}. */
    private static final String MIGRACION = "db/changelog/schema-v143-destinos-no-servibles.sql";

    @Autowired
    private UnserviceableZoneService zones;

    /**
     * Repone la lista ejecutando la MIGRACIÓN, no una copia escrita en el test.
     *
     * <p>{@code BaseIntegration} vacía todas las tablas antes de cada prueba —es lo que hace predecibles
     * las cuentas del resto de la suite— y se lleva por delante también esta, que la siembra Liquibase al
     * arrancar. Sembrar aquí los rangos a mano dejaría el test comprobándose a sí mismo: lo que tiene que
     * fallar si alguien se equivoca al teclear un código postal es justo el contenido de ese fichero, así
     * que se ejecuta tal cual.
     */
    @BeforeEach
    void reponerLaListaDelTransportista() throws IOException {
        String sql = new String(new ClassPathResource(MIGRACION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbcTemplate.execute(sql);
    }

    @Test
    @DisplayName("España: Baleares, Canarias, Ceuta y Melilla no se sirven")
    void espanaExcluyeIslasYCiudadesAutonomas() {
        assertThat(zones.isUnserviceable("ES", "07001")).as("Palma de Mallorca").isTrue();
        assertThat(zones.isUnserviceable("ES", "35001")).as("Las Palmas de Gran Canaria").isTrue();
        assertThat(zones.isUnserviceable("ES", "38001")).as("Santa Cruz de Tenerife").isTrue();
        assertThat(zones.isUnserviceable("ES", "51001")).as("Ceuta").isTrue();
        assertThat(zones.isUnserviceable("ES", "52001")).as("Melilla").isTrue();
    }

    @Test
    @DisplayName("España: la península sí se sirve")
    void espanaPeninsularSeSirve() {
        assertThat(zones.isUnserviceable("ES", "28001")).as("Madrid").isFalse();
        assertThat(zones.isUnserviceable("ES", "08001")).as("Barcelona").isFalse();
        assertThat(zones.isUnserviceable("ES", "50004")).as("Zaragoza").isFalse();
        assertThat(zones.isUnserviceable("ES", "36001")).as("Pontevedra, justo por encima del rango canario")
                .isFalse();
    }

    @Test
    @DisplayName("Portugal: Azores y Madeira no se sirven, en el formato con guion que usa el país")
    void portugalExcluyeArchipielagos() {
        // El código postal portugués es NNNN-NNN. Al quitarle el guion queda un número de siete cifras,
        // así que el rango tiene que estar sembrado en ESE formato o la exclusión no atrapa nada.
        assertThat(zones.isUnserviceable("PT", "9500-321")).as("Ponta Delgada (Azores)").isTrue();
        assertThat(zones.isUnserviceable("PT", "9000-001")).as("Funchal (Madeira)").isTrue();
        assertThat(zones.isUnserviceable("PT", "1000-001")).as("Lisboa").isFalse();
        assertThat(zones.isUnserviceable("PT", "4000-001")).as("Oporto").isFalse();
    }

    @Test
    @DisplayName("Francia: el ultramar entero queda fuera; la metrópoli no")
    void franciaExcluyeElUltramar() {
        assertThat(zones.isUnserviceable("FR", "97110")).as("Guadalupe").isTrue();
        assertThat(zones.isUnserviceable("FR", "97400")).as("Reunión").isTrue();
        assertThat(zones.isUnserviceable("FR", "98713")).as("Polinesia Francesa").isTrue();
        assertThat(zones.isUnserviceable("FR", "75001")).as("París").isFalse();
        assertThat(zones.isUnserviceable("FR", "13001")).as("Marsella").isFalse();
    }

    @Test
    @DisplayName("Austria: los rangos con cero a la izquierda atrapan el código postal de cuatro cifras")
    void austriaComparaPorNumeroYNoPorTexto() {
        // Los rangos austriacos se sembraron con cinco cifras ('01500'), y el país usa cuatro ('1500').
        // Comparados como texto no coincidirían nunca; como número, sí.
        assertThat(zones.isUnserviceable("AT", "1500")).isTrue();
        assertThat(zones.isUnserviceable("AT", "1010")).as("centro de Viena").isFalse();
    }

    @Test
    @DisplayName("ante la duda no se bloquea: sin código postal, o con uno que no se puede comparar")
    void anteLaDudaSeDejaPasar() {
        // Impedir una compra por falta de dato es peor que el caso que se intenta evitar.
        assertThat(zones.isUnserviceable("ES", null)).isFalse();
        assertThat(zones.isUnserviceable("ES", "")).isFalse();
        assertThat(zones.isUnserviceable("GB", "SW1A 1AA")).as("los CP británicos no son numéricos").isFalse();
        assertThat(zones.isUnserviceable("US", "10001")).as("país sin exclusiones cargadas").isFalse();
    }

    @Test
    @DisplayName("la migración ha cargado la lista completa del transportista")
    void laListaEstaSembrada() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM carrier_unserviceable_zone",
                Integer.class);
        assertThat(total).as("los 635 rangos de la lista 不可到地区清单").isGreaterThan(600);

        Integer paises = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT country_code) FROM carrier_unserviceable_zone", Integer.class);
        assertThat(paises).as("los 12 países de la UE con exclusiones numéricas").isEqualTo(12);
    }
}
