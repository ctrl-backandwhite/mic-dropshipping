package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MoqMarginSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ajuste (fila única, id = 1) del margen para productos con MOQ &gt; 1. */
public interface MoqMarginSettingRepository extends JpaRepository<MoqMarginSettingEntity, Short> {
}
