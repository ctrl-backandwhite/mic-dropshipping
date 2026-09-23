package com.nexaplatform.dropshipping.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Si hay un solo {@code @PreAuthorize} en la aplicación, la seguridad de método tiene que estar activada.
 *
 * <p>Sin {@code @EnableMethodSecurity}, Spring no crea los proxies y la anotación NO SE EJECUTA: es texto.
 * Hoy no abre ningún agujero —las tres rutas que la llevan cuelgan de {@code /api/admin/**}, que la cadena
 * ya cierra por URL—, pero es peor que no tenerla: el siguiente que escriba
 * {@code @PreAuthorize("hasRole('OPERATOR')")} en un endpoint bajo {@code /api/me/**} creerá que lo ha
 * restringido, y quedará abierto a cualquier usuario autenticado sin un solo síntoma.
 *
 * <p>Las dos salidas válidas son activarla o no usar la anotación. Esta prueba impide la tercera, que es
 * la que había: usarla y que no haga nada.
 */
class SeguridadDeMetodoActivaTest {

    @Test
    @DisplayName("si se usa @PreAuthorize, @EnableMethodSecurity está declarada")
    void siSeUsaPreAuthorizeLaSeguridadDeMetodoEstaActivada() throws IOException {
        List<String> conPreAuthorize = new ArrayList<>();
        boolean activada = false;

        for (Path fuente : ficherosJava()) {
            String texto = Files.readString(fuente, StandardCharsets.UTF_8);
            if (texto.contains("@PreAuthorize")) {
                conPreAuthorize.add(fuente.getFileName().toString());
            }
            if (texto.contains("@EnableMethodSecurity")) {
                activada = true;
            }
        }

        if (conPreAuthorize.isEmpty()) {
            return; // Nadie la usa: la autorización es solo por URL y no hace falta activarla.
        }
        assertThat(activada).as("%s usan @PreAuthorize y no hay @EnableMethodSecurity: esas anotaciones no se ejecutan",
                conPreAuthorize).isTrue();
    }

    private static List<Path> ficherosJava() throws IOException {
        try (Stream<Path> rutas = Files.walk(Path.of("src/main/java"))) {
            return rutas.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
