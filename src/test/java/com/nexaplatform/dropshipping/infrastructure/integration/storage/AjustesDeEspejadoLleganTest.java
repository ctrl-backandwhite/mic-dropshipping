package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Que los ajustes del espejado LLEGUEN de verdad a la aplicación.
 *
 * <p>Por qué existe: el 5-sep-2026 se descubrió que
 * {@code nexadrop.storage.mirror-concurrency} nunca se declaró en {@code application.yml}. El
 * {@code @Value} de {@link ImageMirrorService} caía siempre en su valor por defecto de 4, y la
 * variable {@code STORAGE_MIRROR_CONCURRENCY} que los tres entornos llevaban meses declarando **no
 * hacía absolutamente nada**.
 *
 * <p>No se notó porque no falla: el arranque no protesta, el registro no dice nada y el valor por
 * defecto es razonable. Se destapó cuando bajarlo a 1 para frenar unas caídas no las frenó, y el
 * registro seguía enseñando cuatro hilos comprimiendo a la vez.
 *
 * <p>Esta prueba recorre los {@code @Value} de los servicios de espejado y comprueba que cada
 * propiedad que nombran esté declarada en {@code application.yml}. Un ajuste que no llega es peor que
 * no tenerlo: hace creer que algo está bajo control cuando no lo está.
 */
@DisplayName("Ajustes del espejado · que lleguen de verdad")
class AjustesDeEspejadoLleganTest {

    private static final Pattern PROPIEDAD = Pattern.compile("\\$\\{([^:}]+)");

    @Test
    @DisplayName("toda propiedad nexadrop.* que pide el espejado está declarada en application.yml")
    void los_ajustes_estan_declarados() throws IOException {
        String yaml = leer("/application.yml");

        List<String> huerfanas = new ArrayList<>();
        for (Class<?> servicio : List.of(ImageMirrorService.class, VideoMirrorService.class,
                CompresorDeImagen.class)) {
            for (Field campo : servicio.getDeclaredFields()) {
                Value anotacion = campo.getAnnotation(Value.class);
                if (anotacion == null) {
                    continue;
                }
                Matcher m = PROPIEDAD.matcher(anotacion.value());
                if (!m.find()) {
                    continue;
                }
                String propiedad = m.group(1).trim();
                if (!propiedad.startsWith("nexadrop.")) {
                    continue;
                }
                // En el YAML la propiedad aparece como su ÚLTIMO segmento seguido de dos puntos, dentro
                // de su bloque. Basta con buscar esa clave: si no está en ningún sitio, no está.
                String clave = propiedad.substring(propiedad.lastIndexOf('.') + 1);
                if (!yaml.contains(clave + ":")) {
                    huerfanas.add(propiedad + "  (en " + servicio.getSimpleName() + "." + campo.getName() + ")");
                }
            }
        }

        assertThat(huerfanas)
                .as("estas propiedades se leen con @Value pero NO están en application.yml, así que su "
                        + "variable de entorno no llega y el valor por defecto manda siempre — que es "
                        + "exactamente lo que pasó con mirror-concurrency")
                .isEmpty();
    }

    private static String leer(String recurso) throws IOException {
        try (InputStream in = AjustesDeEspejadoLleganTest.class.getResourceAsStream(recurso)) {
            assertThat(in).as("no se encuentra " + recurso).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
