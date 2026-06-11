package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.Supplier;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the Admin Suppliers endpoints; operates on and returns the
 * {@link Supplier} domain model. Covers the admin list (with computed product
 * counts and rating-derived KPIs) and the verified / trustPass toggles.
 */
public interface SupplierUseCase {

    List<Supplier> findAll();

    Supplier toggleVerified(UUID id);

    Supplier toggleTrustPass(UUID id);
}
