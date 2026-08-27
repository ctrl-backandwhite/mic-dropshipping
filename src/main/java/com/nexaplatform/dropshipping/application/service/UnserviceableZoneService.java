package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UnserviceableZoneEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UnserviceableZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ¿Entrega el transportista en ese código postal?
 *
 * <p>La cobertura por país no basta: YunExpress publica una lista de zonas excluidas dentro de países
 * que sí sirve. En España son Baleares, Canarias, Ceuta y Melilla; en Portugal, Azores y Madeira; en
 * Francia, todo el ultramar. Sin esta comprobación se acepta el pedido, se cobra, y al despachar no hay
 * forma de emitir la guía.
 *
 * <p><b>Ante la duda no se bloquea.</b> Sin código postal, o con uno que no se puede comparar con los
 * rangos guardados, se deja pasar: impedir una compra por falta de dato es peor que el caso que se
 * intenta evitar, y hay países donde el código postal ni siquiera es obligatorio.
 */
@Service
@RequiredArgsConstructor
public class UnserviceableZoneService {

    private final UnserviceableZoneRepository repository;

    @Transactional(readOnly = true)
    public boolean isUnserviceable(String countryCode, String postalCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return false;
        }
        Long code = numeric(postalCode);
        if (code == null) {
            return false;
        }
        List<UnserviceableZoneEntity> zones = repository.findByCountryCodeIgnoreCase(countryCode.trim());
        for (UnserviceableZoneEntity z : zones) {
            Long from = numeric(z.getPostalFrom());
            Long to = numeric(z.getPostalTo());
            // Bordes INCLUSIVOS: el rango 07000-07999 cubre Baleares entera, extremos incluidos.
            if (from != null && to != null && code >= from && code <= to) {
                return true;
            }
        }
        return false;
    }

    /** El código postal sin espacios ni guiones, como número; null si no se puede comparar así. */
    private static Long numeric(String postalCode) {
        if (postalCode == null) {
            return null;
        }
        String clean = postalCode.replaceAll("[\\s-]", "");
        if (clean.isEmpty() || !clean.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
