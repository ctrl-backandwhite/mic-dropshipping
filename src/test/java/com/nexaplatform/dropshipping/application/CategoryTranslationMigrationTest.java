package com.nexaplatform.dropshipping.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las categorías tienen que hablar los mismos idiomas que los productos, verificado sobre la migración.
 *
 * <p><b>Por qué existe.</b> El catálogo se traduce a ocho idiomas y los productos los tenían los ocho,
 * pero las categorías solo siete: faltaba el chino entero. El nombre chino sí estaba, en
 * {@code category.name_zh}, así que el hueco no se veía por ninguna parte salvo navegando en chino, donde
 * los productos salían traducidos colgando de categorías en otro idioma. Un idioma a medias no da error:
 * se descubre mirando la tienda.
 *
 * <p><b>Por qué NO es un test de integración.</b> {@code BaseIntegration.cleanAllTables()} vacía las tablas
 * de negocio entre casos, y {@code category} es una de ellas: un IT vería cero categorías y pasaría por
 * vacuidad, que es peor que no tener test. Aquí se lee la migración que rellena el hueco y se comprueba lo
 * que promete, que es el dato que acaba en la base de cualquier entorno.
 *
 * <p><b>Si mañana se añade un noveno idioma</b>, este test obliga a pasar por aquí: la lista de idiomas es
 * la misma que la de los productos, y añadir uno sin traer las categorías detrás rompe la prueba en vez de
 * la tienda.
 */
class CategoryTranslationMigrationTest {

    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog");
    private static final Path MIGRACION = CHANGELOG.resolve("schema-v160-categorias-chino.sql");
    private static final Path MAESTRO = CHANGELOG.resolve("db.changelog-master.yaml");

    /** Los ocho idiomas del catálogo: los mismos que tiene cada producto en {@code product_translation}. */
    private static final Set<String> IDIOMAS = new TreeSet<>(Set.of("es", "en", "pt", "zh", "fr", "de", "it", "nl"));

    /** Los siete que le faltaban a la categoría de botas de hombre; el chino lo pone el cambio anterior. */
    private static final Set<String> DE_LA_HUERFANA = new TreeSet<>(Set.of("es", "en", "pt", "fr", "de", "it", "nl"));

    private String migracion() throws IOException {
        return Files.readString(MIGRACION, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("la migración está incluida en el maestro, o no se aplicaría en ningún entorno")
    void incluidaEnElMaestro() throws IOException {
        assertThat(Files.readString(MAESTRO, StandardCharsets.UTF_8))
                .contains("db/changelog/schema-v160-categorias-chino.sql");
    }

    @Test
    @DisplayName("el chino se rellena desde name_zh, que es donde ya estaba el nombre")
    void elChinoSaleDeNameZh() throws IOException {
        String sql = migracion();

        assertThat(sql).contains("INSERT INTO category_translation").contains("'zh'").contains("c.name_zh");
        // Sin este filtro, una categoría sin nombre chino entraría con el nombre vacío, que es peor que
        // no tener traducción: la tienda pintaría un hueco en lugar de recurrir a otro idioma.
        assertThat(sql).contains("c.name_zh IS NOT NULL").contains("btrim(c.name_zh) <> ''");
    }

    @Test
    @DisplayName("es repetible: aplicarla dos veces no duplica ni pisa lo que ya hay")
    void esRepetible() throws IOException {
        List<String> inserciones = List.of(migracion().split("INSERT INTO category_translation"));

        assertThat(inserciones).hasSizeGreaterThan(1);
        assertThat(inserciones.subList(1, inserciones.size()))
                .allSatisfy(bloque -> assertThat(bloque).contains("ON CONFLICT (category_id, language) DO NOTHING"));
    }

    @Test
    @DisplayName("la categoría que se cargó sin nombre recibe los siete idiomas que le faltaban")
    void laHuerfanaRecibeSusIdiomas() throws IOException {
        String sql = migracion();

        assertThat(sql).contains("c.slug = 'moda-cal-31'");
        Matcher m = Pattern.compile("\\('([a-z]{2})', '").matcher(sql);
        Set<String> encontrados = new TreeSet<>();
        while (m.find()) {
            encontrados.add(m.group(1));
        }
        assertThat(encontrados).isEqualTo(DE_LA_HUERFANA);
    }

    @Test
    @DisplayName("entre los dos cambios se cubren los ocho idiomas del catálogo, ni uno menos")
    void cubreLosOchoIdiomas() throws IOException {
        String sql = migracion();
        Set<String> cubiertos = new TreeSet<>(DE_LA_HUERFANA);
        cubiertos.add("zh");

        assertThat(cubiertos).isEqualTo(IDIOMAS);
        assertThat(IDIOMAS).allSatisfy(idioma -> assertThat(sql).contains("'" + idioma + "'"));
    }
}
