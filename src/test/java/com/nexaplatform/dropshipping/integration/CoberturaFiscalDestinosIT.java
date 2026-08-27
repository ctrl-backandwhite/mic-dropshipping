package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ningún destino a la venta puede quedarse sin tipo impositivo.
 *
 * <p>Por qué existe esta prueba. {@code CountryTaxService.rateBpsFor} devuelve 0 tanto si el país tiene un
 * tipo del 0% como si NO tiene fila en {@code country_tax_rate}, y {@code computeTaxCents} corta en
 * {@code bps <= 0} sin avisar a nadie. Los dos casos son indistinguibles desde fuera, así que un destino
 * sin tipo no rompe nada: simplemente se vende sin cobrar el impuesto, y eso solo se descubre cuando el
 * transportista lo repercute en destino y sale del margen. Así estuvieron 40 de los 90 destinos hasta la
 * migración {@code schema-v148-iva-destinos-restantes.sql}.
 *
 * <p>Qué vigila. Que la lista de destinos habilitados en {@code cainiao_shipping_zone} y la de tipos en
 * {@code country_tax_rate} no se separen. El agujero no se abrió por un error de código sino por un
 * desfase entre dos tablas que nadie cruzaba: habilitar un destino nuevo desde el panel, o desde una
 * migración de cobertura como la v144, no obliga a darle tipo. Esta prueba sí.
 *
 * <p>Por qué {@code PersistenceITBase} y no {@code BaseIntegration}: la base de endpoint hace TRUNCATE de
 * todas las tablas antes de cada prueba, así que allí habría que volver a sembrar a mano lo que se quiere
 * comprobar —y la prueba pasaría a comprobarse a sí misma—. Este contexto arranca con el esquema tal y como
 * lo deja Liquibase, que es exactamente el estado que se despliega.
 */
class CoberturaFiscalDestinosIT extends PersistenceITBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int tipoDe(String pais) {
        Integer bps = jdbcTemplate.queryForObject(
                "SELECT rate_bps FROM country_tax_rate WHERE country_code = ?", Integer.class, pais);
        assertThat(bps).as("el país %s no tiene tipo impositivo", pais).isNotNull();
        return bps;
    }

    // ─────────────────────────────────────────────────── el invariante

    @Test
    @DisplayName("todos los destinos habilitados tienen tipo impositivo")
    void todosLosDestinosHabilitadosTienenTipo() {
        // Si esto falla al añadir un destino, no hay que tocar la prueba: hay que darle tipo al destino.
        List<String> huerfanos = jdbcTemplate.queryForList(
                "SELECT z.country_code FROM cainiao_shipping_zone z"
                        + " WHERE z.enabled"
                        + " AND NOT EXISTS (SELECT 1 FROM country_tax_rate t"
                        + "                 WHERE t.country_code = z.country_code)"
                        + " ORDER BY z.country_code",
                String.class);

        assertThat(huerfanos)
                .as("destinos a la venta que se cobrarían sin impuesto")
                .isEmpty();
    }

    @Test
    @DisplayName("el tipo de un destino habilitado está activo, no solo presente")
    void elTipoDeUnDestinoHabilitadoEstaActivo() {
        // Una fila con active = false devuelve 0 igual que si no existiera: el filtro de
        // CountryTaxService la descarta antes de leer el rate_bps.
        List<String> inactivos = jdbcTemplate.queryForList(
                "SELECT z.country_code FROM cainiao_shipping_zone z"
                        + " JOIN country_tax_rate t ON t.country_code = z.country_code"
                        + " WHERE z.enabled AND NOT t.active"
                        + " ORDER BY z.country_code",
                String.class);

        assertThat(inactivos).as("tipos apagados en destinos a la venta").isEmpty();
    }

    @Test
    @DisplayName("la migración cubrió los 90 destinos a la venta")
    void laMigracionCubrioLosNoventaDestinos() {
        Integer habilitados = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cainiao_shipping_zone WHERE enabled", Integer.class);
        Integer tipos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM country_tax_rate", Integer.class);

        assertThat(habilitados).isEqualTo(90);
        assertThat(tipos).as("50 de antes más los 40 que faltaban").isEqualTo(90);
    }

    // ─────────────────────────────────────────────────── los datos, uno a uno

    @Test
    @DisplayName("los tipos europeos que faltaban están sembrados en puntos básicos")
    void losTiposEuropeosQueFaltabanEstanSembrados() {
        // Transcritos a mano desde las fuentes citadas en la migración: un cero de más o de menos aquí es
        // un pedido cobrado con diez veces el impuesto que toca, o con la décima parte.
        assertThat(tipoDe("NO")).as("Noruega 25%").isEqualTo(2500);
        assertThat(tipoDe("IS")).as("Islandia 24%").isEqualTo(2400);
        assertThat(tipoDe("CH")).as("Suiza 8,1%").isEqualTo(810);
        assertThat(tipoDe("LI")).as("Liechtenstein 8,1%, igual que Suiza").isEqualTo(810);
        assertThat(tipoDe("MC")).as("Mónaco 20%, la TVA francesa").isEqualTo(2000);
        assertThat(tipoDe("ME")).as("Montenegro 21%").isEqualTo(2100);
        assertThat(tipoDe("BA")).as("Bosnia y Herzegovina 17%").isEqualTo(1700);
    }

    @Test
    @DisplayName("los tipos con decimales no se han redondeado al entero")
    void losTiposConDecimalesNoSeHanRedondeado() {
        // El campo va en puntos básicos justamente para esto. Redondear el 8,1% suizo al 8% regala 0,1
        // puntos en cada pedido, y el 17,5% de Barbados al 18% se lo cobra de más al cliente.
        assertThat(tipoDe("CH")).as("8,1% y no 800 ni 810 mal escrito").isEqualTo(810);
        assertThat(tipoDe("BB")).as("Barbados 17,5%").isEqualTo(1750);
        assertThat(tipoDe("BZ")).as("Belice 12,5%").isEqualTo(1250);
        assertThat(tipoDe("TT")).as("Trinidad y Tobago 12,5%").isEqualTo(1250);
        assertThat(tipoDe("PR")).as("Puerto Rico 11,5%").isEqualTo(1150);
    }

    @Test
    @DisplayName("Puerto Rico necesita fila propia porque no se resuelve por región de EE. UU.")
    void puertoRicoNecesitaFilaPropia() {
        // Las regiones de EE. UU. que sembró la v73 son los 50 estados y el Distrito de Columbia; PR no
        // está entre ellas y viaja como país en cainiao_shipping_zone, así que rateBpsFor('PR') cae
        // directo a country_tax_rate. Si algún día se añade PR como región de US, esta prueba avisa de que
        // hay dos sitios donde mirar el mismo impuesto.
        Integer comoRegion = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM country_region WHERE country_code = 'US' AND region_code = 'PR'",
                Integer.class);
        Integer comoPais = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cainiao_shipping_zone WHERE country_code = 'PR' AND enabled",
                Integer.class);

        assertThat(comoRegion).isZero();
        assertThat(comoPais).isEqualTo(1);
        assertThat(tipoDe("PR")).as("IVU 10,5% estatal + 1% municipal").isEqualTo(1150);
    }

    @Test
    @DisplayName("Kuwait y Catar llevan un 0 declarado, no la ausencia de fila")
    void kuwaitYCatarLlevanUnCeroDeclarado() {
        // Ninguno de los dos ha puesto en vigor el IVA del CCG. Con fila y 0, el panel enseña que la
        // decisión está tomada; sin fila, sería indistinguible de un país que se quedó sin revisar.
        assertThat(tipoDe("KW")).isZero();
        assertThat(tipoDe("QA")).isZero();

        List<String> etiquetas = jdbcTemplate.queryForList(
                "SELECT label FROM country_tax_rate WHERE country_code IN ('KW', 'QA')"
                        + " ORDER BY country_code",
                String.class);
        assertThat(etiquetas).allSatisfy(etiqueta -> assertThat(etiqueta).isNotBlank());
    }

    @Test
    @DisplayName("la migración no pisó los tipos que ya estaban configurados")
    void laMigracionNoPisoLosTiposYaConfigurados() {
        // El INSERT es idempotente y respeta lo que haya ajustado el administrador desde el panel.
        assertThat(tipoDe("ES")).as("España 21%").isEqualTo(2100);
        assertThat(tipoDe("DE")).as("Alemania 19%").isEqualTo(1900);
        assertThat(tipoDe("IL")).as("Israel 18%, corregido en la v73").isEqualTo(1800);
        assertThat(tipoDe("US")).as("EE. UU. no tiene IVA federal; su tipo sale por estado").isZero();
    }
}
