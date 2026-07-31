package com.nexaplatform.dropshipping.api.exception;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traducción de constraints de base de datos a mensajes para la persona que está usando la aplicación.
 *
 * <p>Lo que se protege aquí es que un choque de unicidad NUNCA salga como SQL crudo ("duplicate key value
 * violates unique constraint …"): además de ser incomprensible, filtra nombres de tablas y columnas.
 */
class Cov04ConstraintMessageTest {

    @Test
    void unaConstraintConocidaSeTraduceAAlgoQueElUsuarioPuedeCorregir() {
        assertThat(ConstraintMessage.forConstraint("users_email_key"))
                .isEqualTo("Ya existe un usuario con ese email.");
        assertThat(ConstraintMessage.forConstraint("affiliate_code_key"))
                .contains("código de afiliado");
    }

    @Test
    void elNombreDeLaConstraintSeReconoceEnMayusculasYConEspacios() {
        // Postgres devuelve el nombre tal cual y el parser puede dejar espacios: si no se normalizara,
        // el mensaje bueno se perdería y saldría el error técnico.
        assertThat(ConstraintMessage.forConstraint("  USERS_EMAIL_KEY  "))
                .isEqualTo(ConstraintMessage.forConstraint("users_email_key"));
    }

    @Test
    void unaConstraintSinMapearDevuelveNuloParaQueDecidaElLlamante() {
        // null es la señal de "no sé traducir esto": el humanizador pone entonces su mensaje genérico.
        assertThat(ConstraintMessage.forConstraint("tabla_rara_que_no_existe")).isNull();
        assertThat(ConstraintMessage.forConstraint(null)).isNull();
        assertThat(ConstraintMessage.forConstraint("   ")).isNull();
    }

    @Test
    void cadaConstraintEstaMapeadaUnaSolaVez() {
        // Dos entradas con el mismo nombre harían que el mensaje mostrado dependiera del orden del enum.
        Set<String> vistas = new HashSet<>();
        for (ConstraintMessage c : ConstraintMessage.values()) {
            assertThat(vistas.add(c.constraint()))
                    .as("constraint duplicada: %s", c.constraint()).isTrue();
        }
    }

    @Test
    void todaEntradaTieneNombreEnMinusculasYMensajeUtil() {
        for (ConstraintMessage c : ConstraintMessage.values()) {
            assertThat(c.constraint()).as("nombre de %s", c.name()).isNotBlank()
                    .isEqualTo(c.constraint().toLowerCase());
            // El nombre en minúsculas no es cosmético: forConstraint compara con el nombre normalizado,
            // así que una entrada con mayúsculas nunca llegaría a encontrarse.
            assertThat(c.message()).as("mensaje de %s", c.name()).isNotBlank()
                    .doesNotContain("violates").doesNotContain("ERROR:").doesNotContain("constraint");
        }
    }

    @Test
    void todasLasConstraintsDeclaradasSeEncuentranPorSuNombre() {
        for (ConstraintMessage c : ConstraintMessage.values()) {
            assertThat(ConstraintMessage.forConstraint(c.constraint()))
                    .as("no se encuentra %s", c.name()).isEqualTo(c.message());
        }
    }
}
