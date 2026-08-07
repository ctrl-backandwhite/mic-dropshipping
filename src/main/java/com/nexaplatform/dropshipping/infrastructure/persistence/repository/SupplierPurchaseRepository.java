package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.domain.enums.SupplierPurchaseStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acceso a las compras a proveedores de 1688. */
public interface SupplierPurchaseRepository extends JpaRepository<SupplierPurchaseEntity, UUID> {

    List<SupplierPurchaseEntity> findByOrderId(UUID orderId);

    Optional<SupplierPurchaseEntity> findByOrderIdAndSupplierId(UUID orderId, UUID supplierId);

    /** La cola de trabajo del admin, de lo más antiguo a lo más reciente. */
    List<SupplierPurchaseEntity> findByStatusInOrderByCreatedAtAsc(Collection<SupplierPurchaseStatus> statuses);

    boolean existsByOrderId(UUID orderId);

    /**
     * ¿Queda alguna compra de este pedido sin salir hacia el almacén?
     *
     * <p>Es el freno de la guía internacional: mientras haya un bulto sin comprar o sin despachar, no
     * hay mercancía completa que enviar y crear la guía solo arranca el reloj del seguimiento en falso.
     */
    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<SupplierPurchaseStatus> statuses);
}
