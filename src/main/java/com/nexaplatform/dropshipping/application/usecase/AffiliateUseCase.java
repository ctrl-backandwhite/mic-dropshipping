package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.Affiliate;

import java.util.UUID;

/** Use-case port for affiliate accounts; operates on the {@link Affiliate} domain model. */
public interface AffiliateUseCase extends BaseUseCase<Affiliate, Affiliate, UUID> {

    /** Returns the user's affiliate account, creating one (with a generated code) on first access. */
    Affiliate getOrCreate(UUID userId);
}
