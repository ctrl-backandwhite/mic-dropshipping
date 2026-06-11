package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminSupplierMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
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
import java.util.UUID;

/**
 * Use-case service for the Admin Suppliers endpoints. Holds all logic that used
 * to
 * live inside {@code AdminSupplierController}: listing with cached product
 * counts and
 * rating-derived KPIs, plus the verified / trustPass toggles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSupplierService {

    private final SupplierRepository supplierRepository;
    private final AdminSupplierMapper adminSupplierMapper;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<AdminSupplierDtoOut> list() {
        Map<UUID, Long> productCount = productCountBySupplier();
        return supplierRepository.findAll().stream()
                .map(s -> {
                    BigDecimal rating = s.getRating() != null ? s.getRating() : BigDecimal.valueOf(4.0);
                    double r = rating.doubleValue();
                    long onTimePct = Math.round(Math.min(99.5, 60 + r * 8)); // r=4.0 -> 92, r=5.0 -> 99
                    double defectRate = Math.round((5.0 - r) * 80) / 100.0; // r=5.0 -> 0.0, r=4.0 -> 0.8
                    int responseHours = r >= 4.7 ? 4 : (r >= 4.3 ? 12 : 24);
                    int leadTimeDays = r >= 4.7 ? 3 : (r >= 4.3 ? 7 : 14);
                    return adminSupplierMapper.toView(s,
                            productCount.getOrDefault(s.getId(), 0L),
                            onTimePct, defectRate, responseHours, leadTimeDays);
                })
                .toList();
    }

    /**
     * DROP-585: alterna el flag {@code verified} del proveedor desde el panel
     * admin.
     */
    @Transactional
    public AdminSupplierToggleDtoOut toggleVerified(UUID id) {
        SupplierEntity s = supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier"));
        s.setVerified(!s.isVerified());
        supplierRepository.save(s);
        return adminSupplierMapper.toVerifiedToggle(s);
    }

    @Transactional
    public AdminSupplierToggleDtoOut toggleTrustPass(UUID id) {
        SupplierEntity s = supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier"));
        s.setTrustPass(!s.isTrustPass());
        supplierRepository.save(s);
        return adminSupplierMapper.toTrustPassToggle(s);
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
}
