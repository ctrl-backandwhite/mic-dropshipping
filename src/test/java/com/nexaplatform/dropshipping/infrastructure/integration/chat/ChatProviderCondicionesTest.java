package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SIEMPRE tiene que existir un ChatProvider, esté el asistente encendido o
 * apagado.
 *
 * Cuando no lo había, no fallaba el chat: fallaba el ARRANQUE ENTERO de la
 * aplicación, porque el caso de uso lo exige en su constructor. Ocurrió el
 * 27-ago-2026 en los tres entornos a la vez, con el asistente en su valor por
 * defecto (apagado).
 */
@DisplayName("Proveedor del asistente · siempre hay uno, encendido o apagado")
class ChatProviderCondicionesTest {

    @Configuration
    @ComponentScan(basePackageClasses = ChatProvider.class)
    static class Escaneo {
        // Lo único que el proveedor real necesita para construirse.
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Escaneo.class);

    @Test
    @DisplayName("apagado: queda el suplente, que declara no estar disponible")
    void apagado() {
        runner.withPropertyValues("nexadrop.chat.enabled=false").run(ctx -> {
            assertThat(ctx).hasSingleBean(ChatProvider.class);
            assertThat(ctx.getBean(ChatProvider.class).name()).isEqualTo("noop");
            assertThat(ctx.getBean(ChatProvider.class).available()).isFalse();
        });
    }

    @Test
    @DisplayName("sin configurar nada: también queda el suplente, no cero proveedores")
    void ausente() {
        // Este es el caso que tumbaba el arranque: la propiedad no está definida.
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ChatProvider.class);
            assertThat(ctx.getBean(ChatProvider.class).name()).isEqualTo("noop");
        });
    }

    @Test
    @DisplayName("encendido: queda el real, y solo el real")
    void encendido() {
        runner.withPropertyValues(
                "nexadrop.chat.enabled=true",
                "nexadrop.chat.api-key=clave-de-prueba").run(ctx -> {
            assertThat(ctx).hasSingleBean(ChatProvider.class);
            assertThat(ctx.getBean(ChatProvider.class).name()).isNotEqualTo("noop");
        });
    }
}
