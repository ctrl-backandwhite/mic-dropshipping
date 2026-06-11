package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;

import java.util.List;
import java.util.UUID;

/** Use-case port for intelligence alerts (DROP-71); operates on the {@link IntelligenceAlert} domain model. */
public interface IntelligenceAlertUseCase {

    /** Lists the active alerts owned by the given user. */
    List<IntelligenceAlert> findActiveForUser(UUID userId);

    /** Creates an alert for the given user, applying channel/active defaults. */
    IntelligenceAlert create(UUID userId, IntelligenceAlert model);

    /** Soft-deletes (deactivates) the alert with the given id. */
    void deactivate(UUID id);
}
