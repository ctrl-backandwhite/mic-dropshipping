package com.nexaplatform.dropshipping.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La cobertura de envío que la tienda ANUNCIA, verificada sobre las migraciones que la definen.
 *
 * <p><b>Por qué existe.</b> El escaparate enseña una bandera por destino, y una bandera es una promesa de
 * entrega. Durante mucho tiempo fueron 86, heredadas de {@code cainiao_shipping_zone} —la tabla del
 * transportista ANTERIOR—: la v85 montó la cobertura de YunExpress quitándole Asia y África a la lista de
 * Cainiao, con tarifas que su propio comentario llamaba «mock hasta el rate card real». Era provisional y
 * así se quedó. Nadie comprobó nunca si YunExpress entrega de verdad en esos países, y el que no entrega
 * no da un error: se descubre cuando el pedido ya está cobrado y no hay forma de cursarlo.
 *
 * <p><b>Por qué NO es un test de integración.</b> {@code BaseIntegration.cleanAllTables()} vacía todas las
 * tablas de negocio entre casos, y la de cobertura es una de ellas: un IT vería siempre cero destinos y
 * pasaría por vacuidad, que es peor que no tener test. Aquí se leen las dos migraciones que definen la
 * lista —la que la siembra y la que la recorta— y se comprueba el resultado, que es el dato que acaba en
 * la base de cualquier entorno.
 *
 * <p><b>Cuando llegue el rate card real de YunExpress</b>, este es el sitio donde se actualiza la lista, y
 * entonces sí con una fuente que zanje la duda. Mientras tanto, añadir un destino obliga a pasar por aquí
 * y justificarlo — que es exactamente la conversación que no se tuvo la primera vez.
 */
class ShippingCoverageMigrationTest {

    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog");
    private static final Path SIEMBRA = CHANGELOG.resolve("schema-v85-yunexpress-shipping-coverage.sql");
    private static final Path RECORTE = CHANGELOG.resolve("schema-v130-cobertura-real-yunexpress.sql");

    /**
     * Los 45 destinos con entrega documentada por el transportista: la Unión Europea completa, el Espacio
     * Económico Europeo, Reino Unido y Suiza, América del Norte, los cuatro de Latinoamérica que YunExpress
     * cita por su nombre, los tres de Oriente Medio con operación declarada, y Australia y Nueva Zelanda.
     * Puerto Rico entra porque su última milla es USPS, que el transportista documenta expresamente.
     */
    private static final Set<String> ESPERADOS = new TreeSet<>(
            Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT",
                    "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "NO", "LI", "GB", "CH", "US",
                    "CA", "MX", "PR", "BR", "AR", "CL", "CO", "AE", "SA", "IL", "AU", "NZ"));

    @Test
    @DisplayName("tras el recorte quedan exactamente los destinos con entrega documentada")
    void laCoberturaResultanteEsLaDocumentada() throws IOException {
        Set<String> sembrados = paisesSembrados();
        Set<String> retirados = paisesRetirados();

        Set<String> quedan = new TreeSet<>(sembrados);
        quedan.removeAll(retirados);

        // Se comparan como conjuntos ordenados para que el mensaje de fallo diga qué código sobra o falta;
        // con 45 elementos, un «expected X but was Y» sin ordenar no se puede leer.
        assertThat(quedan).as("destinos que la tienda anunciaría tras aplicar la v130")
                .containsExactlyElementsOf(ESPERADOS);
    }

    @Test
    @DisplayName("el recorte no menciona ningún país que no estuviera sembrado")
    void elRecorteNoTocaPaisesInexistentes() throws IOException {
        // Un código en el UPDATE que no exista en la siembra no falla en Postgres —el UPDATE afecta a cero
        // filas— pero delata que la lista se escribió a mano contra una idea equivocada de qué había.
        assertThat(paisesRetirados()).as("países del recorte que no estaban en la cobertura")
                .isSubsetOf(paisesSembrados());
    }

    @Test
    @DisplayName("la migración declara el mismo total que realmente deja")
    void elTotalDeclaradoCuadra() throws IOException {
        // La v130 lleva su propia comprobación (`RAISE EXCEPTION` si no quedan 45). Si alguien cambia la
        // lista y olvida ese número, el despliegue reventaría en el entorno más inoportuno; mejor aquí.
        Matcher m = Pattern.compile("se esperaban (\\d+)").matcher(Files.readString(RECORTE, StandardCharsets.UTF_8));
        assertThat(m.find()).as("la v130 debe declarar cuántos destinos espera").isTrue();

        Set<String> quedan = new TreeSet<>(paisesSembrados());
        quedan.removeAll(paisesRetirados());
        assertThat(Integer.parseInt(m.group(1))).as("total declarado en la migración").isEqualTo(quedan.size());
    }

    /** Códigos de país de los {@code VALUES} de la siembra: {@code (gen_random_uuid(),'ES','España',…)}. */
    private Set<String> paisesSembrados() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("gen_random_uuid\\(\\),\\s*'([A-Z]{2})'")
                .matcher(Files.readString(SIEMBRA, StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Códigos del {@code IN (…)} del recorte, ignorando los comentarios que lo acompañan. */
    private Set<String> paisesRetirados() throws IOException {
        String sql = Files.readString(RECORTE, StandardCharsets.UTF_8);
        String lista = sql.substring(sql.indexOf("country_code IN ("), sql.indexOf(");"));
        // Fuera los comentarios: llevan palabras en mayúsculas (AE, SA, IL) que si no se colarían como
        // países retirados — y son justo los que SÍ se quedan.
        lista = lista.replaceAll("--[^\\n]*", "");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Z]{2})'").matcher(lista);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
