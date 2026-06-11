package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.AffiliateUseCase;
import com.nexaplatform.dropshipping.domain.model.Affiliate;
import com.nexaplatform.dropshipping.domain.repository.AffiliateRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Affiliate use case. Operates on the {@link Affiliate} model and delegates
 * persistence to the domain port. Holds the get-or-create logic that used to live
 * in {@code AcademyController}: returning the authenticated user's affiliate
 * account or creating a new active one with a generated code on first access. The
 * {@link UserRepository} is kept as a read collaborator to derive the code from
 * the user's display name / email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffiliateUseCaseImpl implements AffiliateUseCase {

    private final AffiliateRepository affiliateRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public Affiliate getOrCreate(UUID userId) {
        return affiliateRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserEntity user = userRepository.findById(userId).orElseThrow();
                    return affiliateRepository.save(Affiliate.builder()
                            .userId(userId)
                            .code(generateCode(user))
                            .active(true)
                            .build());
                });
    }

    /** Builds a short, unique affiliate code from the user's display name or email. */
    private static String generateCode(UserEntity u) {
        String base = (u.getDisplayName() == null ? u.getEmail() : u.getDisplayName())
                .toLowerCase().replaceAll("[^a-z0-9]", "");
        if (base.length() > 8) {
            base = base.substring(0, 8);
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
