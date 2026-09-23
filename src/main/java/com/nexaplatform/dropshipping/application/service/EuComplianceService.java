package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.EuOperatorRole;
import com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategorySafetyWarningEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategorySafetyWarningTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.EuResponsiblePersonEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategorySafetyWarningRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.EuResponsiblePersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cumplimiento del Reglamento (UE) 2023/988 de seguridad general de los productos (aplicable desde el
 * 13-dic-2024) y del Reglamento (UE) 2019/1020 al que remite.
 *
 * <p>Resuelve las tres piezas que la norma obliga a publicar:
 * <ul>
 *   <li><b>Operador económico en la Unión</b> (art. 16.1 y 16.3): nombre, dirección postal y correo
 *       electrónico. Sin él el producto no puede introducirse en el mercado.</li>
 *   <li><b>Fabricante</b> (art. 19.a): nombre, dirección y correo, en la propia oferta en línea.</li>
 *   <li><b>Advertencias de seguridad</b> (art. 19.d), heredadas por la jerarquía de categorías.</li>
 * </ul>
 *
 * <p>El ámbito del 2023/988 es todo producto de consumo salvo medicamentos, alimentos, piensos y seres vivos
 * (art. 2.2): alcanza al catálogo entero, no solo a los eléctricos, las gafas de sol y los relojes que
 * además caen bajo la lista cerrada del art. 4.5 del 2019/1020.
 *
 * <p>Las lecturas cacheadas están en {@link EuComplianceLookup} para que el proxy de caché actúe de verdad.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EuComplianceService {

    private final EuResponsiblePersonRepository responsibleRepository;
    private final CategorySafetyWarningRepository warningRepository;
    private final EuComplianceLookup lookup;

    /**
     * Operador económico tal y como se publica de cara al comprador.
     *
     * @param complete false cuando falta algún dato obligatorio del art. 16.3 (dirección postal completa o
     *                 correo electrónico). Se expone para que el panel de admin pueda avisar; el escaparate
     *                 solo recibe el bloque cuando además está habilitado.
     */
    public record ResponsiblePersonView(String name, String addressLine, String postalCode, String city, String region,
            String country, String email, String phone, String role, String roleLabel, boolean enabled,
            boolean complete) {

        /** Dirección en una línea, tal y como se imprime en la factura y en la ficha. */
        public String formattedAddress() {
            StringBuilder sb = new StringBuilder(addressLine == null ? "" : addressLine);
            boolean conCp = postalCode != null && !postalCode.isBlank();
            if (conCp) {
                sb.append(", ").append(postalCode);
            }
            if (city != null && !city.isBlank()) {
                sb.append(conCp ? " " : ", ").append(city);
            }
            if (country != null && !country.isBlank()) {
                sb.append(" (").append(country).append(')');
            }
            return sb.toString();
        }
    }

    /** Bloque de cumplimiento que viaja con la ficha de producto. */
    public record ProductComplianceView(String manufacturerName, String manufacturerAddress, String manufacturerEmail,
            boolean manufacturerComplete, List<String> safetyWarnings, ResponsiblePersonView responsiblePerson) {
    }

    /** Operador económico configurado, esté o no publicable. Lo usa el panel de admin. */
    public Optional<ResponsiblePersonView> responsible(String lang) {
        return lookup.responsible(normalizarIdioma(lang));
    }

    /**
     * Igual que {@link #responsible(String)} pero solo cuando puede publicarse: habilitado y con los datos
     * del art. 16.3 completos. Publicar una dirección a medias no cumple la obligación, así que en ese caso
     * es mejor no pintar el bloque y que el aviso del panel lo delate.
     */
    public Optional<ResponsiblePersonView> publishedResponsible(String lang) {
        return responsible(lang).filter(ResponsiblePersonView::enabled).filter(ResponsiblePersonView::complete);
    }

    /** Advertencias de seguridad que alcanzan a una categoría, heredadas de sus ancestros. */
    public List<String> safetyWarnings(UUID categoryId, String lang) {
        return lookup.safetyWarnings(categoryId, normalizarIdioma(lang));
    }

    /** Bloque completo para la ficha: fabricante del producto + advertencias + operador económico. */
    public ProductComplianceView forProduct(UUID categoryId, String manufacturerName, String manufacturerAddress,
            String manufacturerEmail, String lang) {
        boolean completo = EuComplianceLookup.relleno(manufacturerName)
                && EuComplianceLookup.relleno(manufacturerAddress) && EuComplianceLookup.relleno(manufacturerEmail);
        return new ProductComplianceView(manufacturerName, manufacturerAddress, manufacturerEmail, completo,
                safetyWarnings(categoryId, lang), publishedResponsible(lang).orElse(null));
    }

    /**
     * Guarda el operador económico. Vacía el caché porque el dato se lee en cada ficha y en cada factura: si
     * no, un cambio de dirección tardaría hasta cinco minutos en verse, que es justo el fallo que ya costó
     * depurar con el margen en tiempo real.
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EU_COMPLIANCE, allEntries = true)
    @Transactional
    public ResponsiblePersonView updateResponsible(ResponsiblePersonView datos, String actor, String lang) {
        EuResponsiblePersonEntity e = responsibleRepository.findById(EuComplianceLookup.FILA_UNICA)
                .orElseGet(EuResponsiblePersonEntity::new);
        e.setId(EuComplianceLookup.FILA_UNICA);
        e.setEnabled(datos.enabled());
        e.setName(trim(datos.name()));
        e.setAddressLine(trim(datos.addressLine()));
        e.setPostalCode(trimNulable(datos.postalCode()));
        e.setCity(trim(datos.city()));
        e.setRegion(trimNulable(datos.region()));
        e.setCountry(datos.country() == null ? null : datos.country().trim().toUpperCase());
        e.setEmail(datos.email() == null ? null : datos.email().trim().toLowerCase());
        e.setPhone(trimNulable(datos.phone()));
        e.setRole(EuOperatorRole.from(datos.role()).name());
        e.setUpdatedAt(Instant.now());
        e.setUpdatedBy(actor);
        EuResponsiblePersonEntity guardado = responsibleRepository.save(e);
        log.info("Operador económico de la UE actualizado por {} ({} · {})", actor, guardado.getName(),
                guardado.getCountry());
        return EuComplianceLookup.toView(guardado, normalizarIdioma(lang));
    }

    /** Vacía el caché de cumplimiento. Lo usan las pantallas de advertencias tras crear, editar o borrar. */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EU_COMPLIANCE, allEntries = true)
    public void invalidarCache() {
        // El vaciado lo hace la anotación; el método existe para poder pedirlo desde otros servicios.
    }

    /** Una advertencia declarada en una categoría, con sus textos por idioma. */
    public record SafetyWarningView(UUID id, UUID categoryId, String code, int position, boolean active,
            Map<String, String> texts) {
    }

    /** Advertencias declaradas DIRECTAMENTE en una categoría (sin heredar), para el panel. */
    @Transactional(readOnly = true)
    public List<SafetyWarningView> warningsOfCategory(UUID categoryId) {
        return warningRepository.findByCategoryIdOrderByPositionAsc(categoryId).stream()
                .map(EuComplianceService::toWarningView).toList();
    }

    /**
     * Crea o actualiza una advertencia identificada por (categoría, código).
     *
     * <p>Es un upsert por código y no por id porque así la misma llamada sirve para sembrar y para corregir:
     * el panel no necesita saber si la advertencia ya existía. Los textos que no se manden se conservan, de
     * modo que traducir un idioma no borra los demás.
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EU_COMPLIANCE, allEntries = true)
    @Transactional
    public SafetyWarningView upsertWarning(UUID categoryId, String code, Integer position, Boolean active,
            Map<String, String> texts, String actor) {
        String codigo = code == null ? "" : code.trim().toUpperCase();
        Instant ahora = Instant.now();
        CategorySafetyWarningEntity w = warningRepository.findByCategoryIdOrderByPositionAsc(categoryId).stream()
                .filter(x -> codigo.equalsIgnoreCase(x.getCode())).findFirst().orElseGet(() -> {
                    CategorySafetyWarningEntity nueva = new CategorySafetyWarningEntity();
                    nueva.setId(UUID.randomUUID());
                    nueva.setCategoryId(categoryId);
                    nueva.setCode(codigo);
                    nueva.setCreatedAt(ahora);
                    nueva.setCreatedBy(actor);
                    return nueva;
                });
        if (position != null) {
            w.setPosition(position);
        }
        w.setActive(active == null || active);
        w.setUpdatedAt(ahora);
        w.setUpdatedBy(actor);
        if (texts != null) {
            aplicarTextos(w, texts, ahora);
        }
        return toWarningView(warningRepository.save(w));
    }

    /** Borra una advertencia y sus traducciones. */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EU_COMPLIANCE, allEntries = true)
    @Transactional
    public void deleteWarning(UUID warningId) {
        warningRepository.deleteById(warningId);
    }

    /** Sustituye o añade los textos de los idiomas mandados, dejando intactos los que no vienen. */
    private static void aplicarTextos(CategorySafetyWarningEntity w, Map<String, String> texts, Instant ahora) {
        texts.forEach((idioma, texto) -> {
            if (idioma == null || idioma.isBlank() || texto == null || texto.isBlank()) {
                return;
            }
            String lang = idioma.trim().toLowerCase();
            CategorySafetyWarningTranslationEntity t = w.getTranslations().stream()
                    .filter(x -> lang.equalsIgnoreCase(x.getLanguage())).findFirst().orElseGet(() -> {
                        CategorySafetyWarningTranslationEntity nueva = new CategorySafetyWarningTranslationEntity();
                        nueva.setId(UUID.randomUUID());
                        nueva.setWarningId(w.getId());
                        nueva.setLanguage(lang);
                        nueva.setCreatedAt(ahora);
                        w.getTranslations().add(nueva);
                        return nueva;
                    });
            t.setText(texto.trim());
            t.setUpdatedAt(ahora);
        });
    }

    private static SafetyWarningView toWarningView(CategorySafetyWarningEntity w) {
        Map<String, String> textos = new LinkedHashMap<>();
        w.getTranslations().forEach(t -> textos.put(t.getLanguage(), t.getText()));
        return new SafetyWarningView(w.getId(), w.getCategoryId(), w.getCode(), w.getPosition(), w.isActive(), textos);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trimNulable(String s) {
        String t = trim(s);
        return t == null || t.isEmpty() ? null : t;
    }

    private static String normalizarIdioma(String lang) {
        return lang == null || lang.isBlank() ? "es" : lang.trim().toLowerCase();
    }
}
