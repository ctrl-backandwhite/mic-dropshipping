package com.nexaplatform.dropshipping.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El validador aborta el arranque en pro/pre, así que un error suyo no se manifiesta como un fallo de
 * prueba sino como un entorno que no levanta. Estas pruebas cubren las dos formas de equivocarse: leer una
 * propiedad que no existe, y no distinguir un secreto inseguro de uno bueno.
 */
class StartupSecretsValidatorTest {

    /** Clave de juguete: el validador sólo mira que no esté vacía, no descifra nada con ella. */
    private static final String KEK_VALIDA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /**
     * La prueba que faltaba. {@code crypto.token-keks} no existía —la propiedad cuelga de {@code nexadrop:}—
     * así que el validador la leía vacía SIEMPRE y tumbaba el arranque en pre/pro por un secreto que estaba
     * bien configurado. Ninguna prueba de comportamiento lo habría visto: con {@code @Value} inyectado a mano
     * el nombre de la clave da igual. Hay que confrontarlo con el fichero de configuración real.
     */
    @Test
    @DisplayName("cada @Value del validador apunta a una clave que existe en application.yml")
    void lasPropiedadesDelValidadorExistenEnLaConfiguracion() throws Exception {
        Properties configuracion = cargarApplicationYml();
        List<String> claves = clavesLeidasPor(StartupSecretsValidator.class);

        assertThat(claves).isNotEmpty();
        assertThat(claves).allSatisfy(clave -> assertThat(configuracion)
                .as("el validador lee '%s', que no está definida en application.yml", clave)
                .containsKey(clave));
    }

    @Test
    @DisplayName("en dev no se exige nada: el arranque local sigue siendo cómodo")
    void enDevNoSeValida() {
        StartupSecretsValidator validador = validador("dev", "", "", "", "");

        assertThatCode(validador::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("en pre, con los cuatro secretos bien puestos, el arranque continúa")
    void enPreConSecretosBuenosArranca() {
        StartupSecretsValidator validador = validador("pre", KEK_VALIDA, "hmac-propio-de-produccion",
                "nxpre9169321edbf4963d", "clave-de-almacenamiento-propia");

        assertThatCode(validador::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("en pre, una KEK vacía aborta el arranque antes de servir tráfico")
    void enPreSinKekAborta() {
        StartupSecretsValidator validador = validador("pre", "", "hmac-propio", "acceso-propio", "secreta");

        assertThatThrownBy(validador::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NX_TOKEN_KEKS");
    }

    @Test
    @DisplayName("en pro, el secreto de baja por defecto aborta el arranque")
    void enProConUnsubscribePorDefectoAborta() {
        StartupSecretsValidator validador = validador("pro", KEK_VALIDA,
                "dev-unsubscribe-secret-change-me", "acceso-propio", "secreta");

        assertThatThrownBy(validador::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_UNSUBSCRIBE_SECRET");
    }

    /** El access key trivial es tan inseguro como uno vacío: es el que trae el fichero de ejemplo. */
    @Test
    @DisplayName("en pre, el access key de almacenamiento por defecto aborta el arranque")
    void enPreConAccessKeyPorDefectoAborta() {
        StartupSecretsValidator validador = validador("pre", KEK_VALIDA, "hmac-propio", "nexadrop", "secreta");

        assertThatThrownBy(validador::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORAGE_ACCESS_KEY");
    }

    private static StartupSecretsValidator validador(String perfil, String kek, String unsubscribe,
                                                     String accessKey, String secretKey) {
        MockEnvironment entorno = new MockEnvironment();
        entorno.setActiveProfiles(perfil);
        StartupSecretsValidator validador = new StartupSecretsValidator(entorno);
        ReflectionTestUtils.setField(validador, "tokenKeks", kek);
        ReflectionTestUtils.setField(validador, "unsubscribeSecret", unsubscribe);
        ReflectionTestUtils.setField(validador, "storageAccessKey", accessKey);
        ReflectionTestUtils.setField(validador, "storageSecretKey", secretKey);
        return validador;
    }

    private static Properties cargarApplicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        yaml.afterPropertiesSet();
        Properties propiedades = yaml.getObject();
        assertThat(propiedades).as("no se pudo leer application.yml").isNotNull();
        return propiedades;
    }

    /** Las claves de los {@code @Value} de la clase, ya sin el {@code ${...}} ni el valor por defecto. */
    private static List<String> clavesLeidasPor(Class<?> tipo) {
        List<String> claves = new ArrayList<>();
        for (Field campo : tipo.getDeclaredFields()) {
            Value anotacion = campo.getAnnotation(Value.class);
            if (anotacion == null) {
                continue;
            }
            claves.add(claveDe(anotacion.value()));
        }
        return claves;
    }

    private static String claveDe(String expresion) {
        String interior = expresion.substring(2, expresion.length() - 1);
        int separador = interior.indexOf(':');
        return separador >= 0 ? interior.substring(0, separador) : interior;
    }
}
