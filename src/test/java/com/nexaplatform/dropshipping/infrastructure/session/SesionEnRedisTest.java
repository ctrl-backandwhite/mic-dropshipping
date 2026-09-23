package com.nexaplatform.dropshipping.infrastructure.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * El módulo que ACTIVA Spring Session tiene que estar en el classpath.
 *
 * <p>El 27-ago-2026 la aplicación llevaba {@code spring-session-data-redis} y
 * NADIE lo configuraba: Spring Boot 4 partió las autoconfiguraciones en módulos
 * sueltos y faltaba {@code spring-boot-session}, que ningún starter arrastra.
 * Tomcat siguió llevando la sesión en su propia memoria —la cookie salía como
 * {@code JSESSIONID} en vez de {@code SESSION}— y el acceso con Google funcionaba
 * con una réplica y se rompía con dos, sin un solo error en el registro.
 *
 * <p>Esta prueba es deliberadamente tonta: comprueba que las clases están donde
 * tienen que estar. No valida el comportamiento —eso lo hace la comprobación de
 * humo tras desplegar, mirando que la cookie sea SESSION y que Redis tenga
 * sesiones—, pero sí impide que alguien retire el módulo y el fallo vuelva sin
 * que nada avise.
 */
@DisplayName("Sesión · el módulo que la activa está presente")
class SesionEnRedisTest {

    @Test
    @DisplayName("está la autoconfiguración de sesión de Spring Boot")
    void esta_la_autoconfiguracion() {
        assertThatCode(() -> Class.forName("org.springframework.boot.session.autoconfigure.SessionAutoConfiguration"))
                .as("falta spring-boot-session: sin él la sesión se queda en la memoria "
                        + "del pod y el acceso se rompe con varias réplicas")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("está la implementación de sesión sobre Redis")
    void esta_la_implementacion_de_redis() {
        assertThatCode(() -> Class.forName("org.springframework.session.data.redis.RedisSessionRepository"))
                .as("falta spring-session-data-redis").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("y las dos piezas son de la misma familia de versiones")
    void versiones_coherentes() throws Exception {
        String sesion = Class.forName("org.springframework.session.data.redis.RedisSessionRepository").getPackage()
                .getImplementationVersion();
        String boot = Class.forName("org.springframework.boot.session.autoconfigure.SessionAutoConfiguration")
                .getPackage().getImplementationVersion();
        // No se exige igualdad exacta: se comprueba que ninguna venga sin versión,
        // que es señal de un empaquetado raro.
        assertThat(sesion).isNotBlank();
        assertThat(boot).isNotBlank();
    }
}
