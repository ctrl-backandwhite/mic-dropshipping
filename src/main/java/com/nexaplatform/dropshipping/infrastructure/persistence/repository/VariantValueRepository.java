package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Acceso a los valores de un eje de variación (p.ej. los colores) para renombrar su etiqueta. */
public interface VariantValueRepository extends JpaRepository<VariantValueEntity, UUID> {
}
