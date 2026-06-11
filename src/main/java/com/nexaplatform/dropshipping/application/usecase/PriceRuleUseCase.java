package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.PriceRule;

import java.util.UUID;

/** Use-case port for pricing rules; operates on the {@link PriceRule} domain model. */
public interface PriceRuleUseCase extends BaseUseCase<PriceRule, PriceRule, UUID> {
}
