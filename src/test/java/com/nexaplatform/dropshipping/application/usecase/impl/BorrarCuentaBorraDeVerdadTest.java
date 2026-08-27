package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ejercer el derecho de supresión tiene que suprimir algo.
 *
 * <p>Antes, borrar la cuenta marcaba {@code deletedAt}, ponía {@code active = false} y ahí acababa
 * todo: nombre, email, teléfono y direcciones seguían en la base de datos indefinidamente. Era una
 * desactivación con otro nombre. El art. 17 del RGPD da derecho a que los datos se supriman, no a que
 * se oculten, y una inspección mira la tabla, no la pantalla.
 *
 * <p>La fila sobrevive porque los pedidos la referencian y borrarla rompería la contabilidad —lo que
 * sí hay obligación legal de conservar (art. 17.3.b)—, pero vacía de contenido personal.
 */
@DisplayName("Borrar la cuenta deja de haber datos personales")
class BorrarCuentaBorraDeVerdadTest {

    private static User anonimizada() {
        User user = User.builder()
                .email("cliente.real@example.com")
                .passwordHash("$2a$10$hashRealDeBcrypt")
                .displayName("Ana Pérez García")
                .firstName("Ana").lastName1("Pérez").lastName2("García")
                .companyName("Tienda de Ana S.L.")
                .phone("+34600111222")
                .avatarUrl("https://cdn/avatar/ana.jpg")
                .country("ES").language("es")
                .lastLogin(Instant.now())
                .googleLinked(true)
                .build();
        UserUseCaseImpl.anonymise(user, "$2a$10$hashDeUnSecretoAleatorioQueNadieConoce");
        return user;
    }

    @Test
    void noQuedaNingunDatoQueIdentifiqueALaPersona() {
        User u = anonimizada();

        assertThat(u.getDisplayName()).isNull();
        assertThat(u.getFirstName()).isNull();
        assertThat(u.getLastName1()).isNull();
        assertThat(u.getLastName2()).isNull();
        assertThat(u.getCompanyName()).isNull();
        assertThat(u.getPhone()).isNull();
        assertThat(u.getAvatarUrl()).isNull();
        assertThat(u.getLastLogin()).isNull();
    }

    @Test
    void elEmailDejaDeSerElDeLaPersona() {
        User u = anonimizada();

        assertThat(u.getEmail()).doesNotContain("cliente.real");
        // Dominio reservado por RFC 2606: ese correo no puede entregarse a nadie por accidente.
        assertThat(u.getEmail()).endsWith("@deleted.invalid");
    }

    @Test
    void elEmailLiberadoNoChocaConOtraCuentaBorrada() {
        // Si todas las cuentas borradas compartieran email, la segunda supresión reventaría contra la
        // restricción de unicidad y el usuario se quedaría sin poder ejercer su derecho.
        assertThat(anonimizada().getEmail()).isNotEqualTo(anonimizada().getEmail());
    }

    @Test
    void nadiePuedeVolverAEntrarConLaCuenta() {
        User u = anonimizada();

        assertThat(u.isActive()).isFalse();
        // Sigue siendo un BCrypt válido —para que el login lo compare y responda 401 con normalidad—
        // pero de un secreto aleatorio de 48 bytes que nadie llega a conocer.
        assertThat(u.getPasswordHash()).isNotEqualTo("$2a$10$hashRealDeBcrypt");
        assertThat(u.isGoogleLinked()).isFalse();
        // Y no queda ningún código pendiente que permita reactivarla.
        assertThat(u.getActivationCode()).isNull();
        assertThat(u.getDeletionCode()).isNull();
    }

    @Test
    void quedaConstanciaDeCuandoSeBorro() {
        // Hace falta para acreditar que la solicitud se atendió y cuándo.
        assertThat(anonimizada().getDeletedAt()).isNotNull();
    }
}
