package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.SupplierUseCase;
import com.nexaplatform.dropshipping.domain.model.Supplier;
import com.nexaplatform.dropshipping.domain.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin Suppliers use case. Operates on the {@link Supplier} model and delegates
 * persistence to the domain port. Holds the logic that used to live in
 * {@code AdminSupplierService}: listing with cached product counts and
 * rating-derived KPIs, plus the verified / trustPass toggles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierUseCaseImpl implements SupplierUseCase {

    private final SupplierRepository supplierRepository;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public List<Supplier> findAll() {
        Map<UUID, Long> productCount = productCountBySupplier();
        return supplierRepository.findAll().stream().map(s -> {
            BigDecimal rating = s.getRating() != null ? s.getRating() : BigDecimal.valueOf(4.0);
            double r = rating.doubleValue();
            long onTimePct = Math.round(Math.min(99.5, 60 + r * 8)); // r=4.0 -> 92, r=5.0 -> 99
            double defectRate = Math.round((5.0 - r) * 80) / 100.0; // r=5.0 -> 0.0, r=4.0 -> 0.8
            int responseHours = r >= 4.7 ? 4 : (r >= 4.3 ? 12 : 24);
            int leadTimeDays = r >= 4.7 ? 3 : (r >= 4.3 ? 7 : 14);
            return s.withProductCount(productCount.getOrDefault(s.getId(), 0L)).withOnTimePct(onTimePct)
                    .withDefectRate(defectRate).withResponseHours(responseHours).withLeadTimeDays(leadTimeDays);
        }).toList();
    }

    /**
     * DROP-585: alterna el flag {@code verified} del proveedor desde el panel
     * admin.
     */
    @Override
    @Transactional
    public Supplier toggleVerified(UUID id) {
        Supplier s = getById(id);
        s.setVerified(!s.isVerified());
        return supplierRepository.update(s);
    }

    @Override
    @Transactional
    public Supplier toggleTrustPass(UUID id) {
        Supplier s = getById(id);
        s.setTrustPass(!s.isTrustPass());
        return supplierRepository.update(s);
    }

    private Supplier getById(UUID id) {
        Supplier model = supplierRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Supplier");
        }
        return model;
    }

    private Map<UUID, Long> productCountBySupplier() {
        @SuppressWarnings("unchecked")
        List<Object[]> counts = em.createQuery(
                "SELECT p.supplier.id, COUNT(p) FROM ProductEntity p WHERE p.supplier IS NOT NULL GROUP BY p.supplier.id")
                .getResultList();
        Map<UUID, Long> productCount = new HashMap<>();
        for (Object[] row : counts)
            productCount.put((UUID) row[0], (Long) row[1]);
        return productCount;
    }

    @Override
    @Transactional
    public Supplier create(Supplier model) {
        if (model.getName() == null || model.getName().isBlank()) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException("El nombre del proveedor es obligatorio");
        }
        model.setId(null);
        model.setSource(model.getSource() != null ? model.getSource() : "manual");
        if (model.getExternalId() == null || model.getExternalId().isBlank()) {
            model.setExternalId("manual-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (model.getCountry() == null || model.getCountry().isBlank()) {
            model.setCountry("CN");
        }
        Supplier saved = supplierRepository.save(model);
        log.info("::> [SUPPLIER] Created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public Supplier update(UUID id, Supplier model) {
        Supplier existing = supplierRepository.getById(id);
        if (Objects.isNull(existing)) {
            throw new NotFoundException("Proveedor no encontrado");
        }
        if (model.getName() != null && !model.getName().isBlank()) existing.setName(model.getName());
        if (model.getNameZh() != null) existing.setNameZh(model.getNameZh());
        if (model.getCountry() != null && !model.getCountry().isBlank()) existing.setCountry(model.getCountry());
        if (model.getCity() != null) existing.setCity(model.getCity());
        if (model.getRating() != null) existing.setRating(model.getRating());
        if (model.getYearsActive() != null) existing.setYearsActive(model.getYearsActive());
        if (model.getProfileUrl() != null) existing.setProfileUrl(model.getProfileUrl());
        existing.setVerified(model.isVerified());
        existing.setTrustPass(model.isTrustPass());
        return supplierRepository.update(existing);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Supplier existing = supplierRepository.getById(id);
        if (Objects.isNull(existing)) {
            throw new NotFoundException("Proveedor no encontrado");
        }
        Long products = em.createQuery("SELECT count(p) FROM ProductEntity p WHERE p.supplier.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        if (products != null && products > 0) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException(
                    "No se puede eliminar: el proveedor tiene " + products + " productos asociados");
        }
        supplierRepository.delete(id);
        log.info("::> [SUPPLIER] Deleted id={}", id);
    }

}
