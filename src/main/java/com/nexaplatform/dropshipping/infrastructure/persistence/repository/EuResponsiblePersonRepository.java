package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.EuResponsiblePersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso al operador económico de la UE. Fila única: se lee siempre por {@code id = 1}. */
public interface EuResponsiblePersonRepository extends JpaRepository<EuResponsiblePersonEntity, Short> {
}
