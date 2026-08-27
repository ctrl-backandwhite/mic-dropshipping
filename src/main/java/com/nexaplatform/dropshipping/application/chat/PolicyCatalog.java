package com.nexaplatform.dropshipping.application.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Los hechos sobre envíos, plazos, aduanas y devoluciones que el asistente puede
 * afirmar. Van en un fichero del proyecto y no en la base de datos por una razón
 * práctica: son texto que compromete a la empresa ante un cliente, así que pasan
 * por revisión de código como cualquier otra cosa que se publica.
 *
 * <p>
 * Se cargan una vez al arrancar. Si el fichero solo tiene instrucciones y
 * comentarios —como viene de fábrica— el catálogo queda vacío y el asistente
 * responde que no lo sabe, que es justo lo que debe hacer: inventarse una
 * condición de devolución es peor que no contestar.
 */
@Slf4j
@Component
public class PolicyCatalog {

    private static final String RUTA = "chat/politicas.md";
    private static final Pattern COMENTARIOS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private final String hechos;

    public PolicyCatalog() {
        this.hechos = cargar();
    }

    /** Los hechos publicables, o cadena vacía si no se ha escrito ninguno. */
    public String hechos() {
        return hechos;
    }

    public boolean vacio() {
        return hechos.isBlank();
    }

    private String cargar() {
        ClassPathResource recurso = new ClassPathResource(RUTA);
        if (!recurso.exists()) {
            log.info("Sin catálogo de políticas ({}): el asistente no responderá sobre condiciones", RUTA);
            return "";
        }
        try (InputStream in = recurso.getInputStream()) {
            String crudo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Fuera los comentarios: son instrucciones para quien edita el fichero, no hechos que
            // el asistente deba contar a un cliente. Y fuera la cabecera de instrucciones, que va
            // hasta el primer encabezado de sección.
            String sinComentarios = COMENTARIOS.matcher(crudo).replaceAll("");
            Matcher primeraSeccion = Pattern.compile("(?m)^## ").matcher(sinComentarios);
            if (!primeraSeccion.find()) {
                return "";
            }
            return sinComentarios.substring(primeraSeccion.start()).trim();
        } catch (IOException e) {
            log.warn("No se pudo leer {}: {}", RUTA, e.getMessage());
            return "";
        }
    }
}
