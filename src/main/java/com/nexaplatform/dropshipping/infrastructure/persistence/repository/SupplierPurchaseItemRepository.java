package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Acceso a las líneas de pedido que cubre cada compra al proveedor. */
public interface SupplierPurchaseItemRepository extends JpaRepository<SupplierPurchaseItemEntity, UUID> {

    List<SupplierPurchaseItemEntity> findByPurchaseId(UUID purchaseId);

    /** Las líneas de varias compras de golpe, para pintar la cola sin una consulta por fila. */
    List<SupplierPurchaseItemEntity> findByPurchaseIdIn(Collection<UUID> purchaseIds);
}
