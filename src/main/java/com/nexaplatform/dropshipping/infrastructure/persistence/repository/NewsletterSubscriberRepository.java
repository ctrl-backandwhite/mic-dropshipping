package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterSubscriberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NewsletterSubscriberRepository extends JpaRepository<NewsletterSubscriberEntity, UUID> {

    Optional<NewsletterSubscriberEntity> findByEmailIgnoreCase(String email);

    Optional<NewsletterSubscriberEntity> findByToken(String token);

    List<NewsletterSubscriberEntity> findByStatus(String status);

    long countByStatus(String status);
}
