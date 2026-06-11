package com.nexaplatform.dropshipping.infrastructure.persistence.repository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface MentorProfileRepository extends JpaRepository<MentorProfileEntity, UUID> {
    List<MentorProfileEntity> findByActiveTrueOrderByCreatedAtDesc();
    Optional<MentorProfileEntity> findByUser_Id(UUID userId);
}
