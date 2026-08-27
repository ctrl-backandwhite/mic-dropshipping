package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WelcomeExampleSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Fila única (id = 1) con los productos fijados a mano para la guía de bienvenida. */
public interface WelcomeExampleSettingRepository extends JpaRepository<WelcomeExampleSettingEntity, Short> {
}
