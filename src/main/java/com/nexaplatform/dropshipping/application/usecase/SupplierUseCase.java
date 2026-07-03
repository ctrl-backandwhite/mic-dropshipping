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

    /** A page of admin suppliers: the enriched models for this page + the grand total. */
    record SupplierPage(List<Supplier> items, int page, int size, long total) {
    }

    List<Supplier> findAll();

    /**
     * Paginated admin listing ordered most-recent-first. Primary source is the OpenSearch
     * {@code suppliers} index; falls back to a paginated SQL query when the index is unavailable.
     */
    SupplierPage pageAdmin(String q, String country, Boolean verified, int page, int size);

    Supplier toggleVerified(UUID id);

    /** Sets the verified flag of a supplier to a specific value (bulk verify/unverify). */
    Supplier setVerified(UUID id, boolean verified);

    Supplier toggleTrustPass(UUID id);

    /** Creates a supplier manually. */
    Supplier create(Supplier model);

    /** Updates a supplier's editable fields. */
    Supplier update(UUID id, Supplier model);

    /** Deletes a supplier (refused if it still has products). */
    void delete(UUID id);
}
