package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryCustomsProfileEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryCustomsProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Siembra en cada producto los datos que el transportista exige y que la ficha de 1688 no da:
 * partida arancelaria, material y uso declarados, presencia de batería y medidas del paquete.
 *
 * <p>Se aplica en la importación para que ningún producto nuevo entre al catálogo sin poder cotizarse
 * ni declararse. Nunca pisa un dato que venga informado en la importación: el perfil de la categoría es
 * el valor por defecto, no la verdad.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsProfileService {

    private final CategoryCustomsProfileRepository profileRepository;

    /**
     * Perfil de una categoría-hoja: primero el suyo propio y, si no lo tiene, el de su familia
     * ({@code moda-muj-01} → {@code moda-muj-*}).
     */
    public Optional<CategoryCustomsProfileEntity> resolve(String categorySlug) {
        if (categorySlug == null || categorySlug.isBlank()) {
            return Optional.empty();
        }
        String slug = categorySlug.trim();
        Optional<CategoryCustomsProfileEntity> exact = profileRepository.findByCategorySlug(slug);
        if (exact.isPresent()) {
            return exact;
        }
        String[] parts = slug.split("-");
        if (parts.length < 2) {
            return Optional.empty();
        }
        return profileRepository.findByCategorySlug(parts[0] + "-" + parts[1] + "-*");
    }

    /**
     * Completa los huecos aduaneros y de embalaje de {@code product} con el perfil de su categoría.
     * Devuelve {@code true} si tocó algo.
     */
    public boolean applyDefaults(ProductEntity product, String categorySlug) {
        if (isNoneOrBlank(product.getBatteryType())) {
            product.setBatteryType("NONE"); // columna obligatoria: nunca puede quedar nula
        }
        Optional<CategoryCustomsProfileEntity> found = resolve(categorySlug);
        if (found.isEmpty()) {
            log.warn("Sin perfil aduanero para la categoría {}: el producto {} queda sin HS code ni medidas de paquete",
                    categorySlug, product.getExternalId());
            return false;
        }
        CategoryCustomsProfileEntity profile = found.get();
        // Las dos mitades se evalúan SIEMPRE (no se usa ||, que cortocircuitaría): la segunda también
        // tiene huecos que rellenar aunque la primera ya haya tocado algo.
        boolean touchedCustoms = applyCustomsDefaults(product, profile);
        boolean touchedPackaging = applyPackagingDefaults(product, profile);
        return touchedCustoms || touchedPackaging;
    }

    /** Partida arancelaria, material, uso declarado y tipo de batería: lo que exige la declaración. */
    private static boolean applyCustomsDefaults(ProductEntity product, CategoryCustomsProfileEntity profile) {
        boolean touched = false;
        if (isBlank(product.getHsCode()) && !isBlank(profile.getHsCode())) {
            product.setHsCode(profile.getHsCode());
            touched = true;
        }
        if (isBlank(product.getCustomsMaterial()) && !isBlank(profile.getMaterial())) {
            product.setCustomsMaterial(profile.getMaterial());
            touched = true;
        }
        if (isBlank(product.getCustomsUsage()) && !isBlank(profile.getUsageText())) {
            product.setCustomsUsage(profile.getUsageText());
            touched = true;
        }
        if (isNoneOrBlank(product.getBatteryType()) && !isNoneOrBlank(profile.getBatteryType())) {
            product.setBatteryType(profile.getBatteryType());
            touched = true;
        }
        return touched;
    }

    /** Medidas del paquete: sin ellas el transportista no puede cotizar el envío. */
    private static boolean applyPackagingDefaults(ProductEntity product, CategoryCustomsProfileEntity profile) {
        boolean touched = false;
        if (isEmpty(product.getLengthMm()) && !isEmpty(profile.getPackLengthMm())) {
            product.setLengthMm(profile.getPackLengthMm());
            touched = true;
        }
        if (isEmpty(product.getWidthMm()) && !isEmpty(profile.getPackWidthMm())) {
            product.setWidthMm(profile.getPackWidthMm());
            touched = true;
        }
        if (isEmpty(product.getHeightMm()) && !isEmpty(profile.getPackHeightMm())) {
            product.setHeightMm(profile.getPackHeightMm());
            touched = true;
        }
        return touched;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isNoneOrBlank(String value) {
        return isBlank(value) || "NONE".equalsIgnoreCase(value.trim());
    }

    private static boolean isEmpty(Integer value) {
        return value == null || value <= 0;
    }
}
