package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code MentorProfileRepository} domain port. */
public interface MentorProfileJpaRepositoryAdapter extends JpaRepository<MentorProfileEntity, UUID> {

    List<MentorProfileEntity> findByActiveTrueOrderByCreatedAtDesc();
}
