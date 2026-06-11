package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentLiteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingQuoteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingRequestDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.SourcingDtoMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AgentProfileEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingQuoteEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingRequestEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AgentProfileRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SourcingQuoteRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SourcingRequestRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * DROP-3: use-case service for sourcing requests, the agents marketplace and
 * competing quotes. Holds all the logic that used to live inside
 * {@code SourcingController}.
 */
@Service
@RequiredArgsConstructor
public class SourcingService {

    private final SourcingRequestRepository reqRepo;
    private final SourcingQuoteRepository quoteRepo;
    private final AgentProfileRepository agentRepo;
    private final UserRepository userRepo;
    private final SourcingDtoMapper sourcingDtoMapper;

    /* ---------- requests ---------- */

    @Transactional(readOnly = true)
    public List<SourcingRequestDtoOut> myRequests(UUID userId) {
        return reqRepo.findByUser_IdOrderByCreatedAtDesc(userId).stream().map(this::toView).toList();
    }

    @Transactional
    public SourcingRequestDtoOut create(UUID userId, String url, String titleHint, String notes) {
        UserEntity u = userRepo.findById(userId).orElseThrow();

        // Plan quota — DROP-36: Free 0/5, Plus 30, Prime 60 per 30-day window.
        long used = reqRepo.countByUser_IdAndCreatedAtAfter(userId, Instant.now().minus(30, ChronoUnit.DAYS));
        String plan = "FREE";  // simplified: tied to subscription, default FREE=5
        int quota = switch (plan) { case "PLUS" -> 30; case "PRIME" -> 60; default -> 5; };
        if (used >= quota) throw new BusinessException("Sourcing quota reached for plan " + plan + " (" + used + "/" + quota + ")");

        // Detect source from URL.
        String src = null;
        String ext = null;
        String low = url.toLowerCase();
        if (low.contains("1688.com"))            src = "1688";
        else if (low.contains("taobao.com"))     src = "taobao";
        else if (low.contains("aliexpress.com")) src = "aliexpress";
        else if (low.contains("ebay.com"))       src = "ebay";

        SourcingRequestEntity r = SourcingRequestEntity.builder()
                .user(u).sourceUrl(url)
                .source(src).externalId(ext)
                .status("PENDING").titleHint(titleHint)
                .notes(notes).planQuota(plan)
                .build();
        return toView(reqRepo.save(r));
    }

    @Transactional(readOnly = true)
    public SourcingRequestDtoOut detail(UUID userId, UUID id) {
        return toView(require(userId, id));
    }

    // DROP-552: cancel (status=CANCELLED) a sourcing request.
    @Transactional
    public SourcingRequestDtoOut cancel(UUID userId, UUID id) {
        SourcingRequestEntity r = require(userId, id);
        r.setStatus("CANCELLED");
        return toView(reqRepo.save(r));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        SourcingRequestEntity r = require(userId, id);
        // Only allow deletion if there are no accepted quotes yet.
        boolean hasApproved = quoteRepo.findByRequest_IdOrderByPriceUsdCentsAsc(id).stream()
                .anyMatch(q -> "ACCEPTED".equalsIgnoreCase(q.getStatus()));
        if (hasApproved) {
            throw new BusinessException("Cannot delete: this sourcing request already has an accepted quote.");
        }
        reqRepo.delete(r);
    }

    @Transactional(readOnly = true)
    public List<SourcingQuoteDtoOut> quotes(UUID userId, UUID id) {
        require(userId, id);
        return quoteRepo.findByRequest_IdOrderByPriceUsdCentsAsc(id).stream()
                .map(sourcingDtoMapper::toQuote).toList();
    }

    @Transactional
    public SourcingQuoteDtoOut submitQuote(UUID id, int priceUsdCents, int etaDays, Integer moq, String notes, UUID asAgent) {
        SourcingRequestEntity r = reqRepo.findById(id).orElseThrow(() -> new NotFoundException("Sourcing request"));
        AgentProfileEntity agent = asAgent == null ? null
                : agentRepo.findById(asAgent).orElseThrow(() -> new NotFoundException("Agent"));
        SourcingQuoteEntity quote = SourcingQuoteEntity.builder()
                .request(r).agent(agent)
                .priceUsdCents(priceUsdCents).etaDays(etaDays)
                .moq(moq).notes(notes).status("OPEN").build();
        quote = quoteRepo.save(quote);
        if (!"QUOTING".equals(r.getStatus())) { r.setStatus("QUOTING"); reqRepo.save(r); }
        return sourcingDtoMapper.toQuote(quote);
    }

    @Transactional
    public SourcingRequestDtoOut selectQuote(UUID userId, UUID id, UUID quoteId) {
        SourcingRequestEntity r = require(userId, id);
        SourcingQuoteEntity q = quoteRepo.findById(quoteId).orElseThrow(() -> new NotFoundException("Quote"));
        if (!q.getRequest().getId().equals(r.getId())) throw new BusinessException("Quote does not belong to this request");
        q.setStatus("ACCEPTED");
        quoteRepo.save(q);
        r.setStatus("APPROVED");
        r.setSelectedQuoteId(q.getId());
        return toView(reqRepo.save(r));
    }

    /* ---------- agents marketplace ---------- */

    @Transactional(readOnly = true)
    public List<SourcingAgentLiteDtoOut> agentsList() {
        return agentRepo.findByActiveTrueOrderBySatisfactionDesc().stream()
                .map(sourcingDtoMapper::toAgentLite).toList();
    }

    @Transactional(readOnly = true)
    public SourcingAgentDetailDtoOut agentDetail(UUID id) {
        AgentProfileEntity a = agentRepo.findById(id).orElseThrow(() -> new NotFoundException("Agent"));
        return sourcingDtoMapper.toAgentDetail(a);
    }

    /* ---------- helpers ---------- */

    private SourcingRequestEntity require(UUID userId, UUID id) {
        SourcingRequestEntity r = reqRepo.findById(id).orElseThrow(() -> new NotFoundException("Sourcing request"));
        if (r.getUser() == null || !userId.equals(r.getUser().getId())) throw new NotFoundException("Sourcing request");
        return r;
    }

    private SourcingRequestDtoOut toView(SourcingRequestEntity r) {
        int count = quoteRepo.findByRequest_IdOrderByPriceUsdCentsAsc(r.getId()).size();
        return sourcingDtoMapper.toRequest(r, count);
    }
}
