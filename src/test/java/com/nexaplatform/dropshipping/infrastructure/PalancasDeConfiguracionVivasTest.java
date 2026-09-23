package com.nexaplatform.dropshipping.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toda propiedad {@code nexadrop.*} que el código lee tiene que estar declarada en
 * {@code application.yml}.
 *
 * <p>La variable de entorno no llega al código por sí sola: llega porque el {@code yml} la mapea
 * ({@code email: ${ALERTS_EMAIL:...}}). Un {@code @Value("${nexadrop.x:algo}")} cuya clave no está en el
 * fichero se queda SIEMPRE con su valor por defecto, y quien ponga la variable en el entorno verá que no
 * pasa nada — sin error, sin aviso y sin nada en el registro. Es una palanca muerta: parece que existe y
 * no hace nada.
 *
 * <p>Ya ha pasado con {@code nexadrop.alerts.*}, cuyo bloque colgaba de {@code logging:} en vez de
 * {@code nexadrop:}: los avisos de «el transportista no acepta envíos» o «la pasarela no cobra» iban
 * siempre al buzón por defecto, y {@code ALERTS_ENABLED=false} no los apagaba. Esta prueba lo caza al
 * escribirlo, no en producción.
 */
class PalancasDeConfiguracionVivasTest {

    private static final Pattern VALUE_NEXADROP = Pattern.compile("\\$\\{(nexadrop\\.[A-Za-z0-9._-]+)");

    @Test
    @DisplayName("ninguna propiedad nexadrop.* que el código lee falta en application.yml")
    void ningunaPalancaDeConfiguracionEstaMuerta() throws IOException {
        Properties configuracion = cargarApplicationYml();
        List<String> huerfanas = new ArrayList<>();

        for (Path fuente : ficherosJava()) {
            String texto = Files.readString(fuente, StandardCharsets.UTF_8);
            Matcher m = VALUE_NEXADROP.matcher(texto);
            while (m.find()) {
                String clave = m.group(1);
                if (!configuracion.containsKey(clave)) {
                    huerfanas.add(clave + "  (" + fuente.getFileName() + ")");
                }
            }
        }

        assertThat(huerfanas).as("estas propiedades se leen en el código y no están en application.yml: la variable de "
                + "entorno correspondiente no hace nada").isEmpty();
    }

    private static List<Path> ficherosJava() throws IOException {
        try (Stream<Path> rutas = Files.walk(Path.of("src/main/java"))) {
            return rutas.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Todos los ficheros de configuración, no solo el común: una palanca declarada solo en el perfil de
     * producción sigue estando viva allí, y exigirla también en el común sería un falso positivo.
     */
    private static Properties cargarApplicationYml() throws IOException {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        try (Stream<Path> rutas = Files.walk(Path.of("src/main/resources"))) {
            yaml.setResources(rutas.filter(r -> r.getFileName().toString().matches("application.*\\.yml"))
                    .map(r -> new FileSystemResource(r.toFile())).toArray(org.springframework.core.io.Resource[]::new));
        }
        yaml.afterPropertiesSet();
        Properties propiedades = yaml.getObject();
        assertThat(propiedades).as("no se pudieron leer los application*.yml").isNotNull();
        return propiedades;
    }
}
