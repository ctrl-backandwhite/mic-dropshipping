package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.SourcingUseCase;
import com.nexaplatform.dropshipping.domain.model.SourcingAgent;
import com.nexaplatform.dropshipping.domain.model.SourcingQuote;
import com.nexaplatform.dropshipping.domain.model.SourcingRequest;
import com.nexaplatform.dropshipping.domain.repository.SourcingAgentRepository;
import com.nexaplatform.dropshipping.domain.repository.SourcingQuoteRepository;
import com.nexaplatform.dropshipping.domain.repository.SourcingRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Sourcing use case: orchestrates sourcing requests, the agents marketplace and
 * competing quotes. Operates on the domain models and delegates persistence to
 * the domain ports. Holds all the logic that used to live in {@code
 * SourcingService} (and originally in {@code SourcingController}), preserving its
 * behavior, exception messages and per-user ownership checks.
 */
@Service
@RequiredArgsConstructor
public class SourcingUseCaseImpl implements SourcingUseCase {

    private final SourcingRequestRepository sourcingRequestRepository;
    private final SourcingQuoteRepository sourcingQuoteRepository;
    private final SourcingAgentRepository sourcingAgentRepository;

    /* ---------- requests ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<SourcingRequest> myRequests(UUID userId) {
        return sourcingRequestRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::withQuotesCount)
                .toList();
    }

    @Override
    @Transactional
    public SourcingRequest create(UUID userId, String url, String titleHint, String notes) {
        // Plan quota — DROP-36: Free 0/5, Plus 30, Prime 60 per 30-day window.
        long used = sourcingRequestRepository.countByUserIdAndCreatedAtAfter(userId,
                Instant.now().minus(30, ChronoUnit.DAYS));
        String plan = "FREE"; // simplified: tied to subscription, default FREE=5
        int quota = switch (plan) {
            case "PLUS" -> 30;
            case "PRIME" -> 60;
            default -> 5;
        };
        if (used >= quota)
            throw new BusinessException("Sourcing quota reached for plan " + plan + " (" + used + "/" + quota + ")");

        // Detect source from URL.
        String src = null;
        String ext = null;
        String low = url.toLowerCase();
        if (low.contains("1688.com"))
            src = "1688";
        else if (low.contains("taobao.com"))
            src = "taobao";
        else if (low.contains("aliexpress.com"))
            src = "aliexpress";
        else if (low.contains("ebay.com"))
            src = "ebay";

        SourcingRequest model = SourcingRequest.builder().userId(userId).sourceUrl(url).source(src).externalId(ext)
                .status("PENDING").titleHint(titleHint).notes(notes).planQuota(plan).build();
        return withQuotesCount(sourcingRequestRepository.save(model));
    }

    @Override
    @Transactional(readOnly = true)
    public SourcingRequest detail(UUID userId, UUID id) {
        return withQuotesCount(require(userId, id));
    }

    // DROP-552: cancel (status=CANCELLED) a sourcing request.
    @Override
    @Transactional
    public SourcingRequest cancel(UUID userId, UUID id) {
        SourcingRequest r = require(userId, id);
        r.setStatus("CANCELLED");
        return withQuotesCount(sourcingRequestRepository.save(r));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID id) {
        require(userId, id);
        // Only allow deletion if there are no accepted quotes yet.
        boolean hasApproved = sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(id).stream()
                .anyMatch(q -> "ACCEPTED".equalsIgnoreCase(q.getStatus()));
        if (hasApproved) {
            throw new BusinessException("Cannot delete: this sourcing request already has an accepted quote.");
        }
        sourcingRequestRepository.delete(id);
    }

    /* ---------- quotes ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<SourcingQuote> quotes(UUID userId, UUID id) {
        require(userId, id);
        return sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(id);
    }

    @Override
    @Transactional
    public SourcingQuote submitQuote(UUID id, int priceUsdCents, int etaDays, Integer moq, String notes, UUID asAgent) {
        SourcingRequest r = sourcingRequestRepository.getById(id);
        if (Objects.isNull(r)) {
            throw new NotFoundException("Sourcing request");
        }
        SourcingAgent agent = null;
        if (asAgent != null) {
            agent = sourcingAgentRepository.getById(asAgent);
            if (Objects.isNull(agent)) {
                throw new NotFoundException("Agent");
            }
        }
        SourcingQuote quote = SourcingQuote.builder().requestId(r.getId()).agent(agent).priceUsdCents(priceUsdCents)
                .etaDays(etaDays).moq(moq).notes(notes).status("OPEN").build();
        quote = sourcingQuoteRepository.save(quote);
        if (!"QUOTING".equals(r.getStatus())) {
            r.setStatus("QUOTING");
            sourcingRequestRepository.save(r);
        }
        return quote;
    }

    @Override
    @Transactional
    public SourcingRequest selectQuote(UUID userId, UUID id, UUID quoteId) {
        SourcingRequest r = require(userId, id);
        SourcingQuote q = sourcingQuoteRepository.getById(quoteId);
        if (Objects.isNull(q)) {
            throw new NotFoundException("Quote");
        }
        if (!q.getRequestId().equals(r.getId()))
            throw new BusinessException("Quote does not belong to this request");
        q.setStatus("ACCEPTED");
        sourcingQuoteRepository.save(q);
        r.setStatus("APPROVED");
        r.setSelectedQuoteId(q.getId());
        return withQuotesCount(sourcingRequestRepository.save(r));
    }

    /* ---------- agents marketplace ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<SourcingAgent> agentsList() {
        return sourcingAgentRepository.findByActiveTrueOrderBySatisfactionDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public SourcingAgent agentDetail(UUID id) {
        SourcingAgent agent = sourcingAgentRepository.getById(id);
        if (Objects.isNull(agent)) {
            throw new NotFoundException("Agent");
        }
        return agent;
    }

    /* ---------- helpers ---------- */

    /** Loads a request, enforcing that it exists and is owned by the given user. */
    private SourcingRequest require(UUID userId, UUID id) {
        SourcingRequest r = sourcingRequestRepository.getById(id);
        if (Objects.isNull(r)) {
            throw new NotFoundException("Sourcing request");
        }
        if (r.getUserId() == null || !userId.equals(r.getUserId()))
            throw new NotFoundException("Sourcing request");
        return r;
    }

    /** Fills the read-only quotes count on the request model. */
    private SourcingRequest withQuotesCount(SourcingRequest r) {
        long count = sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(r.getId()).size();
        r.setQuotesCount(count);
        return r;
    }
}
