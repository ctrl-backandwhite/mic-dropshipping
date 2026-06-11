package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.SourcingAgent;
import com.nexaplatform.dropshipping.domain.model.SourcingQuote;
import com.nexaplatform.dropshipping.domain.model.SourcingRequest;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the Sourcing aggregate: sourcing requests, competing quotes
 * and the agents marketplace. Operates on the domain models
 * ({@link SourcingRequest}, {@link SourcingQuote}, {@link SourcingAgent}); all
 * request operations are scoped to the owning {@code userId}.
 */
public interface SourcingUseCase {

    /* ---------- requests ---------- */

    /** Lists the authenticated user's requests (newest first), with quote counts. */
    List<SourcingRequest> myRequests(UUID userId);

    /** Creates a request for the user, enforcing the plan quota and detecting the source. */
    SourcingRequest create(UUID userId, String url, String titleHint, String notes);

    /** Returns a single request owned by the user. */
    SourcingRequest detail(UUID userId, UUID id);

    /** Cancels (status=CANCELLED) a request owned by the user. */
    SourcingRequest cancel(UUID userId, UUID id);

    /** Deletes a request owned by the user, unless it already has an accepted quote. */
    void delete(UUID userId, UUID id);

    /* ---------- quotes ---------- */

    /** Lists the quotes of a request owned by the user, cheapest first. */
    List<SourcingQuote> quotes(UUID userId, UUID id);

    /** Submits a quote for a request (optionally on behalf of an agent). */
    SourcingQuote submitQuote(UUID id, int priceUsdCents, int etaDays, Integer moq, String notes, UUID asAgent);

    /** Selects the winning quote of a request owned by the user. */
    SourcingRequest selectQuote(UUID userId, UUID id, UUID quoteId);

    /* ---------- agents marketplace ---------- */

    /** Lists the active sourcing agents (by satisfaction). */
    List<SourcingAgent> agentsList();

    /** Returns a single agent profile by id. */
    SourcingAgent agentDetail(UUID id);
}
