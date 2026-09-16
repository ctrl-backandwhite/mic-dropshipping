package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FAIL-CLOSED de secretos en entornos reales (pro/pre).
 *
 * <p>Varios secretos tienen un valor por defecto/ vacío en {@code application.yml} para que el arranque en
 * local sea cómodo. En un entorno de verdad, arrancar con esos valores es un fallo de seguridad silencioso
 * (HMAC falsificable, cifrado con clave efímera, credenciales triviales). Este validador aborta el arranque
 * en pro/pre si alguno sigue en su valor inseguro, para que el problema se vea ANTES de servir tráfico.
 */
@Slf4j
@Component
public class StartupSecretsValidator {

    private static final String DEFAULT_UNSUBSCRIBE = "dev-unsubscribe-secret-change-me";
    private static final String DEFAULT_STORAGE_ACCESS = "nexadrop";

    private final Environment environment;

    // OJO con el prefijo: la propiedad cuelga de `nexadrop:` en application.yml, igual que las otras tres.
    // Sin él la clave no existe, el validador la leía SIEMPRE vacía y abortaba el arranque en pro/pre por un
    // secreto que sí estaba configurado — imposible de arreglar desde el entorno. Lo cubre
    // StartupSecretsValidatorTest, que compara estas claves contra el application.yml real.
    @Value("${nexadrop.crypto.token-keks:}")
    private String tokenKeks;
    @Value("${nexadrop.email.unsubscribe-secret:}")
    private String unsubscribeSecret;
    @Value("${nexadrop.storage.access-key:}")
    private String storageAccessKey;
    @Value("${nexadrop.storage.secret-key:}")
    private String storageSecretKey;
    @Value("${nexadrop.captcha.hmac-key:}")
    private String captchaHmacKey;
    @Value("${nexadrop.security.cdn-shared-secret:}")
    private String cdnSharedSecret;

    public StartupSecretsValidator(Environment environment) {
        this.environment = environment;
    }

    // @PostConstruct (no ApplicationReadyEvent): se ejecuta durante la inicialización del contexto, ANTES de
    // que el servidor embebido abra el puerto. Así un secreto inseguro aborta el arranque sin servir tráfico.
    @PostConstruct
    public void validate() {
        if (!environment.acceptsProfiles(Profiles.of("pro", "pre"))) {
            return; // Solo se exige en entornos reales.
        }
        List<String> problems = new ArrayList<>();
        if (isBlank(tokenKeks)) {
            problems.add("crypto.token-keks (NX_TOKEN_KEKS) vacío: los secretos cifrados at-rest usarían una "
                    + "clave efímera por arranque (irrecuperables tras reinicio).");
        }
        if (isBlank(unsubscribeSecret) || DEFAULT_UNSUBSCRIBE.equals(unsubscribeSecret)) {
            problems.add("nexadrop.email.unsubscribe-secret (EMAIL_UNSUBSCRIBE_SECRET) por defecto/vacío: el "
                    + "HMAC de baja de newsletter sería falsificable.");
        }
        if (isBlank(storageSecretKey) || DEFAULT_STORAGE_ACCESS.equals(storageAccessKey)) {
            problems.add("nexadrop.storage.* (STORAGE_ACCESS_KEY/STORAGE_SECRET_KEY) por defecto/vacío: "
                    + "credenciales de almacenamiento triviales.");
        }
        if (isBlank(captchaHmacKey)) {
            // Sin clave, cada proceso genera la suya: con más de una réplica el reto se firma en un pod y
            // se verifica en otro, y el registro, el contacto, el restablecimiento y el boletín fallan para
            // una parte de la gente sin nada en el registro que lo explique.
            problems.add("nexadrop.captcha.hmac-key (CAPTCHA_HMAC_KEY) vacío: cada réplica firmaría sus "
                    + "propios retos y el CAPTCHA fallaría de forma intermitente.");
        }
        if (isBlank(cdnSharedSecret)) {
            // GeolocalizacionDelCdn exige esta prueba para creerse CF-IPCountry; en blanco vuelve a confiar
            // en la cabecera, y el origen se alcanza sin pasar por el CDN. Quien lo haga elige su país y con
            // él su margen, su IVA y su arancel.
            problems.add("nexadrop.security.cdn-shared-secret (CDN_SHARED_SECRET) vacío: el país del "
                    + "comprador se tomaría de una cabecera falsificable.");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Secretos inseguros en un entorno pro/pre; define sus variables de "
                    + "entorno antes de arrancar:\n - " + String.join("\n - ", problems));
        }
        log.info("::> [SECURITY] Validación de secretos de arranque OK (perfil real)");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
