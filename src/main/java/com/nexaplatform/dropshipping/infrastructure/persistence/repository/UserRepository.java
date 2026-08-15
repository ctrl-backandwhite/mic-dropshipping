package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByActivationCode(String code);

    boolean existsByEmail(String email);

    List<UserEntity> findByRoleOrderByCreatedAtDesc(UserRole role);

    /** Audiencia de marketing: usuarios activos que no han optado por salir de campañas. */
    List<UserEntity> findByActiveTrueAndMarketingOptOutFalse();

    /**
     * Todas las cuentas activas, sin filtrar por el rechazo a la publicidad.
     *
     * <p>Es la audiencia de las comunicaciones de SERVICIO —un cambio en los términos o en la política de
     * privacidad—, que no son marketing y por eso alcanzan también a quien desactivó las campañas: quien
     * rechazó la publicidad no ha renunciado a enterarse de que cambian las condiciones que le vinculan.
     * Para cualquier envío comercial hay que usar el método de arriba, no este.
     */
    List<UserEntity> findByActiveTrueAndDeletedAtIsNull();
}
