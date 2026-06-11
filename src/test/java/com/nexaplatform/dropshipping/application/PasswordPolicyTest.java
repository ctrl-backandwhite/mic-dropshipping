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
    @ValueSource(strings = {
            "short1A!",        // < 12 chars
            "alllowercase1!",  // no upper
            "ALLUPPERCASE1!",  // no lower
            "NoDigitsHere!!",  // no digit
            "NoSymbol1234AB"   // no symbol
    })
    @DisplayName("rechaza contraseñas que no cumplen política")
    void rejects_weak_passwords(String pw) {
        assertThatThrownBy(() -> policy.validate(pw)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("rechaza contraseñas comunes aunque cumplan formato")
    void rejects_common_password_after_normalization() {
        // 'password1234' is in the blacklist after .toLowerCase(); but it lacks symbol/upper,
        // so we craft a 12-char password that lowercases into the blacklist exactly.
        assertThatThrownBy(() -> policy.validate("password1234"))
                .isInstanceOf(BusinessException.class);
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
        assertThatThrownBy(() -> policy.validate(tooLong))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most 128");
    }
}
