package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    @DisplayName("acepta una contraseña fuerte que cumple todos los requisitos")
    void accepts_strong_password() {
        policy.validate("Str0ngP@ssword!");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab1!cde", // < 8 chars (política: mínimo 8)
            "alllowercase1!", // no upper
            "ALLUPPERCASE1!", // no lower
            "NoDigitsHere!!", // no digit
            "NoSymbol1234AB" // no symbol
    })
    @DisplayName("rechaza contraseñas que no cumplen política")
    void rejects_weak_passwords(String pw) {
        assertThatThrownBy(() -> policy.validate(pw)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("las contraseñas de la lista negra caen antes, por las familias que les faltan")
    void rejects_common_password_after_normalization() {
        // La lista COMMON se consulta DESPUÉS de exigir mayúscula, dígito y símbolo, y ninguna de sus
        // entradas los tiene: el rechazo llega siempre por la familia que falta, nunca por "too common".
        // Se rechazan igual —no hay agujero—, pero esa rama no es alcanzable y el test lo dice.
        assertThatThrownBy(() -> policy.validate("password1234"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("uppercase");
        assertThatThrownBy(() -> policy.validate("Qwerty123"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("symbol");
    }

    @Test
    @DisplayName("la familia exigida se detecta esté donde esté en la contraseña")
    void detects_required_family_at_any_position() {
        // Los patrones pasaron de ".*[A-Z].*" con matches() a la clase suelta con find(): el motor ya no
        // recorre y retrocede sobre toda la cadena. Con el carácter exigido al final se comprueba que el
        // barrido no se limita al prefijo.
        assertThat(policy.isAcceptable("aaaaaaaaaaaa1!Z")).isTrue();
        assertThat(policy.isAcceptable("Zaaaaaaaaaaaa1!")).isTrue();
    }

    @Test
    @DisplayName("la más larga admitida sin mayúscula se rechaza sin dispararse")
    void longest_allowed_without_uppercase_is_fast() {
        // Peor caso que el motor llega a ver: 128 caracteres que no casan con [A-Z]. El tope de longitud
        // ya acotaba el coste; esto evita que un patrón con retroceso vuelva a colarse en la validación.
        String limite = "a1!".repeat(42) + "aa";
        assertThat(limite).hasSize(128);
        long inicio = System.nanoTime();
        assertThatThrownBy(() -> policy.validate(limite)).isInstanceOf(BusinessException.class);
        assertThat((System.nanoTime() - inicio) / 1_000_000)
                .as("validar 128 caracteres sin mayúscula").isLessThan(200L);
    }

    @Test
    @DisplayName("isAcceptable devuelve true/false sin lanzar")
    void is_acceptable_helper() {
        assertThat(policy.isAcceptable("Str0ngP@ssword!")).isTrue();
        assertThat(policy.isAcceptable("weak")).isFalse();
    }

    @Test
    @DisplayName("rechaza contraseña por encima del máximo")
    void rejects_too_long() {
        String tooLong = "A1!a".repeat(40);
        assertThatThrownBy(() -> policy.validate(tooLong)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most 128");
    }
}
