package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.EuComplianceService.ResponsiblePersonView;
import com.nexaplatform.dropshipping.domain.enums.EuOperatorRole;
import com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.EuResponsiblePersonEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategorySafetyWarningRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.EuResponsiblePersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lecturas cacheadas del bloque de cumplimiento de la UE.
 *
 * <p>Vive en un bean aparte de {@link EuComplianceService} A PROPÓSITO: {@code @Cacheable} funciona por
 * proxy, y una llamada entre métodos del mismo bean no pasa por él. Si estos métodos estuvieran junto a los
 * que los consumen, el caché quedaría anulado sin que nada fallara —cada ficha volvería a la base de
 * datos— y solo se notaría en la latencia. Es el mismo fallo silencioso que ya se corrigió al limpiar Sonar.
 */
@Component
@RequiredArgsConstructor
public class EuComplianceLookup {

    /** La tabla es de fila única: el operador económico es uno para todo el mercado. */
    static final short FILA_UNICA = 1;

    private final EuResponsiblePersonRepository responsibleRepository;
    private final CategorySafetyWarningRepository warningRepository;

    /**
     * Operador económico configurado, con la etiqueta de su figura ya traducida.
     *
     * <p>Vacío si la fila no existe: es un estado real —entorno recién levantado— y la ficha del comprador
     * tiene que pintarse igualmente; quien avisa es el panel de admin, no un error en el escaparate.
     */
    // Spring DESENVUELVE el Optional antes de guardarlo, así que un Optional.empty() llega al caché como
    // null — y el gestor se configura con allowNullValues(false), de modo que la petición entera revienta.
    // No es un caso raro: es el estado normal de un entorno recién levantado, y tumbaba la ficha de
    // CUALQUIER producto. Con `unless` el vacío no se cachea; lo que se repite es un findById por clave
    // primaria. Va SIN `sync = true` porque Spring no admite las dos cosas a la vez.
    //
    // La condición es `#result == null` y NO `#result.isEmpty()`: dentro de `unless`, Spring ya ha
    // desenvuelto el Optional, así que `#result` es el propio ResponsiblePersonView (o null si estaba
    // vacío). Llamar ahí a isEmpty() lanza SpelEvaluationException y convierte la lectura en un 500.
    @Cacheable(cacheNames = CacheConfig.CACHE_EU_COMPLIANCE, key = "'responsible:' + #lang", unless = "#result == null")
    @Transactional(readOnly = true)
    public Optional<ResponsiblePersonView> responsible(String lang) {
        return responsibleRepository.findById(FILA_UNICA).map(e -> toView(e, lang));
    }

    /** Advertencias de seguridad que alcanzan a una categoría, heredadas de toda su cadena de ancestros. */
    @Cacheable(cacheNames = CacheConfig.CACHE_EU_COMPLIANCE, key = "'warn:' + #categoryId + ':' + #lang", sync = true)
    @Transactional(readOnly = true)
    public List<String> safetyWarnings(UUID categoryId, String lang) {
        if (categoryId == null) {
            return List.of();
        }
        return warningRepository.textosParaCategoria(categoryId, lang);
    }

    /** Convierte la entidad a la vista pública, resolviendo la figura del art. 4.2 y si está completa. */
    static ResponsiblePersonView toView(EuResponsiblePersonEntity e, String lang) {
        EuOperatorRole rol = EuOperatorRole.from(e.getRole());
        // El art. 16.3 exige dirección postal Y correo electrónico. Sin código postal la dirección no
        // permite contactar, así que cuenta como incompleta aunque el resto esté relleno.
        boolean completo = relleno(e.getName()) && relleno(e.getAddressLine()) && relleno(e.getPostalCode())
                && relleno(e.getCity()) && relleno(e.getCountry()) && relleno(e.getEmail());
        return new ResponsiblePersonView(e.getName(), e.getAddressLine(), e.getPostalCode(), e.getCity(), e.getRegion(),
                e.getCountry(), e.getEmail(), e.getPhone(), rol.name(), rol.label(lang), e.isEnabled(), completo);
    }

    static boolean relleno(String s) {
        return s != null && !s.isBlank();
    }
}
