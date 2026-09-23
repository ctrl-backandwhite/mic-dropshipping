package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.LegalUpdateEmailLabel;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Avisa por correo a todo el que tenga cuenta cuando cambian los términos o la política de privacidad.
 *
 * <p><b>Por qué.</b> El usuario queda vinculado por unos textos que aceptó al registrarse. Cambiarlos sin
 * decírselo lo deja obligado por una redacción que nunca vio, y le quita la posibilidad de reaccionar —que
 * es justo lo que da sentido al aviso: si no está de acuerdo, puede cerrar su cuenta—. Por eso el correo
 * incluye esa salida de forma explícita.
 *
 * <p><b>No es publicidad, y por eso va a todos.</b> Comunica un cambio en las condiciones del servicio, así
 * que también lo reciben quienes tienen desactivadas las comunicaciones comerciales. Confundir ambas cosas
 * falla en las dos direcciones: mandar publicidad a quien la rechazó es una infracción, y callar un cambio
 * de condiciones deja al usuario atado a algo que no conoce. El correo no lleva enlace de baja porque no
 * hay lista de la que salirse.
 *
 * <p><b>Cómo se dispara.</b> Al arrancar se compara la versión publicada ({@code nexadrop.legal.version})
 * con las ya avisadas en {@code legal_version_notice}. Si es nueva, se encola el aviso y se marca. La marca
 * es lo que impide que cada reinicio del servicio repita el correo a toda la base de usuarios — y se inserta
 * ANTES de encolar: si algo falla a mitad, es preferible que unos pocos se queden sin aviso a que un
 * reinicio en bucle mande el mismo correo veinte veces.
 *
 * <p>Se encola, no se envía: el envío real lo hace el despachador con sus reintentos, igual que el resto de
 * correos. Con miles de cuentas, mandar en caliente durante el arranque lo bloquearía.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalUpdateNoticeService {

    private static final String TEMPLATE = "emails/legal-update";

    private final UserRepository userRepository;
    private final EmailQueueService emailQueue;
    private final JdbcTemplate jdbcTemplate;

    @Value("${nexadrop.legal.version:}")
    private String legalVersion;

    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String storefrontBaseUrl;

    /** Se desactiva en los tests: allí no interesa encolar correos a la base de usuarios de turno. */
    @Value("${nexadrop.legal.notify-on-change:true}")
    private boolean notifyOnChange;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void avisarSiCambioLaVersion() {
        if (!notifyOnChange || legalVersion == null || legalVersion.isBlank()) {
            return;
        }
        avisarDeVersion(legalVersion);
    }

    /**
     * Avisa de la versión dada si no se ha avisado ya. Lo llama el arranque —cuando se despliega una
     * versión nueva— y el botón de publicar del admin, que es el camino por el que ahora se cambian los
     * textos. Ambos pasan por la misma marca, así que publicar y desplegar no duplican el correo.
     *
     * @return a cuántas cuentas se ha encolado; 0 si esa versión ya estaba avisada.
     */
    @Transactional
    public int avisarDeVersion(String version) {
        if (version == null || version.isBlank() || yaAvisada(version)) {
            return 0;
        }
        // TODAS las cuentas activas, no la audiencia de marketing: quien rechazó la publicidad no ha
        // renunciado a enterarse de que cambian las condiciones que le vinculan.
        List<UserEntity> audiencia = userRepository.findByActiveTrueAndDeletedAtIsNull();

        marcarAvisada(version, audiencia.size());
        int encolados = 0;
        for (UserEntity user : audiencia) {
            if (encolar(user, version)) {
                encolados++;
            }
        }
        log.info("::> [LEGAL] textos legales en versión {} — aviso encolado a {} de {} cuentas activas", version,
                encolados, audiencia.size());
        return encolados;
    }

    /** ¿Ya se avisó de esta versión? La tabla es lo único que distingue un cambio real de un reinicio. */
    private boolean yaAvisada(String version) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM legal_version_notice WHERE version = ?",
                Integer.class, version);
        return n != null && n > 0;
    }

    private void marcarAvisada(String version, int destinatarios) {
        jdbcTemplate.update("INSERT INTO legal_version_notice (version, recipients) VALUES (?, ?)"
                + " ON CONFLICT (version) DO NOTHING", version, destinatarios);
    }

    private boolean encolar(UserEntity user, String version) {
        if (user.getEmail() == null || user.getEmail().isBlank() || user.getDeletedAt() != null) {
            return false;
        }
        String lang = user.getLanguage() == null || user.getLanguage().isBlank()
                ? "es"
                : user.getLanguage().trim().toLowerCase();
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", LegalUpdateEmailLabel.TITLE.of(lang));
        vars.put("intro", LegalUpdateEmailLabel.INTRO.of(lang));
        vars.put("whatToDo", LegalUpdateEmailLabel.WHAT_TO_DO.of(lang));
        vars.put("privacyUrl", storefrontBaseUrl + "/legal/privacy");
        vars.put("privacyLabel", LegalUpdateEmailLabel.CTA_PRIVACY.of(lang));
        vars.put("termsUrl", storefrontBaseUrl + "/legal/terms");
        vars.put("termsLabel", LegalUpdateEmailLabel.CTA_TERMS.of(lang));
        vars.put("version", version);
        vars.put("footerNote", LegalUpdateEmailLabel.FOOTER.of(lang));
        emailQueue.enqueue(user.getEmail(), LegalUpdateEmailLabel.SUBJECT.of(lang), TEMPLATE, vars);
        return true;
    }
}
