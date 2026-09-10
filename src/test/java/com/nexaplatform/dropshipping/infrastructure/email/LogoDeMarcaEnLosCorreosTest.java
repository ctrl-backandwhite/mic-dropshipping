package com.nexaplatform.dropshipping.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Todos los correos llevan el MISMO logo que la web.
 *
 * <p>Quien recibe un correo lo compara con la pantalla de la que viene, y una cabecera distinta se lee
 * como que el correo no es de quien dice ser — que es justo lo que uno mira antes de pinchar en el
 * enlace de una factura.
 *
 * <p>Se comprueba contra los FICHEROS y no montando el servicio: lo que se rompe aquí es que alguien
 * toque una plantilla y se deje el logo, o que el PNG desaparezca del empaquetado. Las dos cosas se
 * ven leyendo, y así la prueba no cuesta un contexto de Spring.
 */
class LogoDeMarcaEnLosCorreosTest {

    private static final Path PLANTILLAS = Path.of("src/main/resources/templates/emails");
    private static final String CID = "cid:circle-nodes";

    /**
     * La plantilla de prueba de la campaña NO se mira: es un `th:replace` de una línea sobre
     * `new-products`, sin cabecera propia. Exigirle el logo obligaría a duplicarlo, que es justo lo que
     * esa plantilla evita.
     */
    private static final List<String> SIN_CABECERA_PROPIA = List.of("new-products-test.html");

    /**
     * Se recorre el DIRECTORIO, no una lista escrita a mano.
     *
     * <p>Es la corrección de un fallo real: la primera versión de esta prueba enumeraba cinco
     * plantillas, y había ocho. Las tres que faltaban dibujaban una caja azul con la letra «N» como
     * logotipo, siguieron haciéndolo después del arreglo y la prueba pasaba en verde, porque no podía
     * ver lo que no estaba en su lista. Una prueba que enumera aquello que vigila solo protege lo que
     * ya se conocía el día que se escribió; en cuanto alguien añade una plantilla, deja de cubrirla y
     * nadie se entera.
     */
    @Test
    void todaslasPlantillasDeCorreoLlevanElLogoDeLaWeb() throws IOException {
        List<String> sinLogo = new ArrayList<>();

        for (Path plantilla : plantillasDeCorreo()) {
            String nombre = plantilla.getFileName().toString();
            if (SIN_CABECERA_PROPIA.contains(nombre)) {
                continue;
            }
            if (!Files.readString(plantilla, StandardCharsets.UTF_8).contains(CID)) {
                sinLogo.add(nombre);
            }
        }

        assertThat(sinLogo).as("estas plantillas de correo no llevan el logo de la marca").isEmpty();
    }

    /**
     * Y no queda ni un rastro del logotipo viejo.
     *
     * <p>Era una caja azul con la letra «N» dentro, dibujada con estilos en línea. No basta con
     * comprobar que el logo bueno está: la factura llegó a tener LOS DOS a la vez —la caja a la
     * izquierda y el icono al lado del nombre— y desde fuera se ve como un correo con dos logotipos.
     */
    @Test
    void noQuedaNingunRastroDelLogotipoViejo() throws IOException {
        List<String> conLaCaja = new ArrayList<>();

        for (Path plantilla : plantillasDeCorreo()) {
            if (Files.readString(plantilla, StandardCharsets.UTF_8).contains(">N</div>")) {
                conLaCaja.add(plantilla.getFileName().toString());
            }
        }

        assertThat(conLaCaja).as("estas plantillas siguen pintando la caja con la letra «N»").isEmpty();
    }

    /**
     * Y el PNG tiene que estar donde el servicio lo busca. Si falta, el correo sale con el hueco de una
     * imagen rota, que es peor que no ponerla: el CID se referencia igual y no se adjunta nada.
     */
    @Test
    void elLogoEstaEmpaquetadoDondeElServicioLoBusca() throws IOException {
        ClassPathResource logo = new ClassPathResource("email-icons/circle-nodes.png");

        assertThat(logo.exists()).as("falta email-icons/circle-nodes.png").isTrue();
        assertThat(logo.contentLength()).as("el logo está vacío").isGreaterThan(200L);
    }

    /**
     * La página de baja se abre pulsando en un correo, así que cuenta como parte de la misma pantalla.
     * Ahí el icono va en SVG y no por CID: es una página, no un correo, y no hay adjuntos que
     * referenciar.
     */
    @Test
    void laPaginaDeBajaLlevaLaMismaMarcaQueLosCorreos() throws IOException {
        String html = sinComentarios(Files.readString(
                Path.of("src/main/resources/templates/pages/unsubscribe-result.html"), StandardCharsets.UTF_8));

        assertThat(html).as("la página de baja no pinta el icono de la marca").contains("<svg");
        assertThat(html).as("la página de baja sigue con la caja de la letra «N»").doesNotContain(">N</div>");
        assertThat(html)
                .as("la página de baja usa un color que no es de la marca")
                .doesNotContain("#7C6CD0");
    }

    /**
     * Los comentarios se quitan ANTES de mirar el marcado.
     *
     * <p>Sin esto la prueba falla por su propia documentación: el comentario que explica de qué morado
     * venía la página menciona el color, y una búsqueda sobre el fichero entero no distingue entre
     * pintar algo de ese color y contar que ya no se pinta. Lo que se vigila es lo que se RENDERIZA.
     */
    private static String sinComentarios(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    private static List<Path> plantillasDeCorreo() throws IOException {
        try (Stream<Path> ficheros = Files.list(PLANTILLAS)) {
            return ficheros.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList();
        }
    }
}
