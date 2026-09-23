package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService;
import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService.ChannelLimit;
import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService.Origen;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los límites de bulto por canal y país, comprobados contra los datos que siembra la migración y contra
 * la pantalla que los mantiene.
 *
 * <p>Por qué no basta el unitario: lo que puede fallar aquí no es la cadena de resolución —eso lo fija
 * {@code CarrierChannelLimitServiceTest}— sino los <b>datos</b>. Los topes se transcribieron a mano desde
 * la sección 六、重量要求 de cada hoja de la cotización; un cero de más en Dinamarca deja pasar guías de
 * 150 kg que el transportista rechaza con el pedido ya cobrado, y un divisor 6000 colado en la línea de
 * ropa le cobra al cliente un peso volumétrico que el carrier no factura.
 *
 * <p>Y se comprueba también el alta desde el panel, porque la tabla nace con nueve columnas
 * {@code NOT NULL}: es exactamente la forma del fallo que costó una certificación en
 * {@code country_customs_rule} —409 al crear, y editar funcionando— y conviene no repetirla.
 */
class CarrierChannelLimitIT extends BaseIntegration {

    /** El fichero que de verdad se despliega. Ver {@link #reponerLosLimitesDelTransportista()}. */
    private static final String MIGRACION = "db/changelog/schema-v145-limites-canal.sql";

    private static final String RUTA = "/api/admin/carrier-limits";
    private static final String ROPA = "FZZXR";
    private static final String CARGA_GENERAL = "THPHR";

    @Autowired
    private CarrierChannelLimitService limites;

    /**
     * Repone los límites ejecutando la MIGRACIÓN, no una copia escrita en el test.
     *
     * <p>{@code BaseIntegration} vacía todas las tablas antes de cada prueba y se lleva por delante
     * también ésta, que la siembra Liquibase al arrancar. Sembrarla aquí a mano dejaría el test
     * comprobándose a sí mismo: lo que tiene que fallar si alguien teclea mal un tope es el contenido de
     * ese fichero, así que se ejecuta tal cual.
     */
    @BeforeEach
    void reponerLosLimitesDelTransportista() throws IOException {
        String sql = new String(new ClassPathResource(MIGRACION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbcTemplate.execute(sql);
    }

    private String tokenAdmin() {
        return jwt.userToken(UUID.randomUUID(), "admin@nx036.local", "ADMIN");
    }

    // ─────────────────────────────────────────────────── los datos sembrados

    @Test
    @DisplayName("la migración siembra los topes de las dos líneas contratadas")
    void laMigracionSiembraLosDosCanales() {
        Integer canales = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT channel_code) FROM carrier_channel_limit",
                Integer.class);
        assertThat(canales).as("la línea de ropa y la de carga general").isEqualTo(2);

        // Cada canal necesita su fila comodín o todos sus destinos caerían a la configuración global.
        Integer comodines = jdbcTemplate
                .queryForObject("SELECT COUNT(*) FROM carrier_channel_limit WHERE country_code = '*'", Integer.class);
        assertThat(comodines).isEqualTo(2);
    }

    @Test
    @DisplayName("Dinamarca admite 15 kg y España 30 kg en la línea de ropa")
    void dinamarcaAdmiteLaMitadQueEspana() {
        assertThat(limites.resolve(ROPA, "DK").maxWeightGrams()).isEqualTo(15000);
        assertThat(limites.resolve(ROPA, "DK").origen()).isEqualTo(Origen.EXACTA);

        // España no es excepción: hereda los 30 kg del canal.
        assertThat(limites.resolve(ROPA, "ES").maxWeightGrams()).isEqualTo(30000);
        assertThat(limites.resolve(ROPA, "ES").origen()).isEqualTo(Origen.CANAL);
    }

    @Test
    @DisplayName("la línea de ropa no aplica volumétrico y la de carga general divide entre 8000")
    void elVolumetricoDependeDelCanal() {
        assertThat(limites.resolve(ROPA, "ES").aplicaVolumetrico()).as("«所有国家：包裹实际重量不计材积»").isFalse();
        assertThat(limites.resolve(CARGA_GENERAL, "ES").volumetricDivisor()).isEqualTo(8000);
    }

    @Test
    @DisplayName("los destinos de 2 kg de la línea de carga general están sembrados")
    void losDestinosDeDosKiloEstanSembrados() {
        // Doce destinos con un tope de 2 kg. Tratarlos como 30 kg significa emitir guías que el
        // transportista rechaza en el almacén de origen.
        assertThat(limites.resolve(CARGA_GENERAL, "PE").maxWeightGrams()).isEqualTo(2000);
        assertThat(limites.resolve(CARGA_GENERAL, "PK").maxWeightGrams()).isEqualTo(2000);
        assertThat(limites.resolve(CARGA_GENERAL, "TZ").maxWeightGrams()).isEqualTo(2000);
    }

    @Test
    @DisplayName("los mínimos facturables por país están sembrados")
    void losMinimosFacturablesEstanSembrados() {
        assertThat(limites.resolve(ROPA, "US").minBillableGrams()).isEqualTo(30);
        assertThat(limites.resolve(ROPA, "CA").minBillableGrams()).isEqualTo(50);
        assertThat(limites.resolve(CARGA_GENERAL, "MX").minBillableGrams()).isEqualTo(20);
    }

    @Test
    @DisplayName("el canal del sandbox no está sembrado y cae a la configuración global")
    void elCanalDelSandboxCaeALaConfiguracion() {
        // BPA no existe en producción. Que caiga a la configuración es lo que mantiene vivo el reparto en
        // el entorno de pruebas, cuyo canal no admite más de 2 kg.
        ChannelLimit bpa = limites.resolve("BPA", "ES");

        assertThat(bpa.origen()).isEqualTo(Origen.GLOBAL);
    }

    // ─────────────────────────────────────────────────── la pantalla de administración

    private static Map<String, Object> limiteNuevo() {
        // LinkedHashMap y no Map.of: hacen falta más de diez claves y el orden ayuda a leer el fallo.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxWeightGrams", 12000);
        body.put("volumetricDivisor", 5000);
        body.put("minBillableGrams", 40);
        body.put("maxLengthMm", 600);
        body.put("maxWidthMm", 400);
        body.put("maxHeightMm", 350);
        body.put("singleParcelOnly", true);
        body.put("notes", "prueba de alta");
        body.put("active", true);
        return body;
    }

    @Test
    @DisplayName("el administrador lista los límites ordenados por canal y país")
    void elAdministradorListaLosLimites() {
        client.get().uri(RUTA).header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin())).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].channelCode").isEqualTo(ROPA).jsonPath("$[0].countryCode").isEqualTo("*");
    }

    @Test
    @DisplayName("se puede dar de alta el límite de un canal y país que no tenían ninguno")
    void seCreaElLimiteDeUnParNuevo() {
        // Nueve columnas NOT NULL: si la entidad no las rellenara, el INSERT del ORM las nombraría a
        // nulo, el valor por defecto de la base no llegaría a aplicarse y esto respondería 409.
        client.put().uri(RUTA + "/" + CARGA_GENERAL + "/PT").header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(limiteNuevo()).exchange().expectStatus().isOk();

        assertThat(limites.resolve(CARGA_GENERAL, "PT").maxWeightGrams()).isEqualTo(12000);
        assertThat(limites.resolve(CARGA_GENERAL, "PT").minBillableGrams()).isEqualTo(40);
    }

    @Test
    @DisplayName("editar un límite ya sembrado lo actualiza en su sitio, sin duplicar la fila")
    void editarNoDuplicaLaFila() {
        client.put().uri(RUTA + "/" + ROPA + "/DK").header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(limiteNuevo()).exchange().expectStatus().isOk();

        Integer filas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carrier_channel_limit" + " WHERE channel_code = 'FZZXR' AND country_code = 'DK'",
                Integer.class);
        assertThat(filas).isEqualTo(1);
        assertThat(limites.resolve(ROPA, "DK").maxWeightGrams()).isEqualTo(12000);
    }

    @Test
    @DisplayName("borrar la excepción de un país devuelve el destino al valor del canal")
    void borrarLaExcepcionDevuelveElDestinoAlValorDelCanal() {
        // Es lo que se quiere cuando el transportista retira una excepción: el país no se queda sin tope,
        // vuelve al del canal.
        client.delete().uri(RUTA + "/" + ROPA + "/DK").header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin()))
                .exchange().expectStatus().isNoContent();

        assertThat(limites.resolve(ROPA, "DK").maxWeightGrams()).isEqualTo(30000);
        assertThat(limites.resolve(ROPA, "DK").origen()).isEqualTo(Origen.CANAL);
    }

    @Test
    @DisplayName("borrar un par que no existe responde 404 y no finge que había algo")
    void borrarLoQueNoExisteResponde404() {
        client.delete().uri(RUTA + "/" + ROPA + "/ZZ").header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin()))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("los límites del transportista solo los toca un ADMIN")
    void soloUnAdminTocaLosLimites() {
        // Es configuración de costes: quien la cambie decide qué se puede despachar y qué no.
        client.get().uri(RUTA).exchange().expectStatus().isEqualTo(401);
        client.get().uri(RUTA).header(HttpHeaders.AUTHORIZATION, bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isEqualTo(403);
        client.delete().uri(RUTA + "/" + ROPA + "/DK").header(HttpHeaders.AUTHORIZATION, bearer(jwt.userToken("USER")))
                .exchange().expectStatus().isEqualTo(403);
    }
}
