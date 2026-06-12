package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterCampaignEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NewsletterCampaignRepository extends JpaRepository<NewsletterCampaignEntity, UUID> {

    List<NewsletterCampaignEntity> findTop20ByOrderByCreatedAtDesc();
}
