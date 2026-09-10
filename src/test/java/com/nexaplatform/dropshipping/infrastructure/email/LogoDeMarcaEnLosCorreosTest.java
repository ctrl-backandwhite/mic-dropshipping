package com.nexaplatform.dropshipping.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El correo lleva el MISMO logo que la web.
 *
 * <p>Antes la cabecera era solo la palabra «NX036» en azul, sin la marca. Quien recibe un correo lo
 * compara con la pantalla de la que viene, y una cabecera distinta se lee como que el correo no es de
 * quien dice ser — que es justo lo que uno mira antes de pinchar en un enlace de una factura.
 *
 * <p>Se comprueba contra los FICHEROS y no montando el servicio: lo que se puede romper aquí es que
 * alguien toque una plantilla y se deje el logo, o que el PNG desaparezca del empaquetado. Las dos
 * cosas se ven leyendo, y así la prueba no cuesta un contexto de Spring.
 */
class LogoDeMarcaEnLosCorreosTest {

    /** Las plantillas con cabecera de marca. Las que no la tienen —avisos internos— no entran. */
    private static final List<String> CON_CABECERA = List.of(
            "welcome.html", "notification.html", "invoice.html",
            "contact-ack.html", "account-deletion-code.html");

    private static final String CID = "cid:circle-nodes";

    @Test
    void todaslasPlantillasConCabeceraLlevanElLogoDeLaWeb() throws IOException {
        for (String plantilla : CON_CABECERA) {
            String html = Files.readString(
                    Path.of("src/main/resources/templates/emails/" + plantilla), StandardCharsets.UTF_8);
            assertThat(html)
                    .as("la plantilla %s no lleva el logo de la marca en su cabecera", plantilla)
                    .contains(CID);
        }
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
}
