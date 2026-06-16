package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductGroupMemberEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductGroupMemberRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRICING_AMOUNT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_SUMMARY;

/**
 * Admin CRUD for product groups (collections of products that share a margin rule via the
 * PRODUCT_GROUP scope). Mutations evict the pricing/product caches and the margin-rule cache so a
 * group change is reflected in prices immediately, across every currency (margin is applied in USD).
 */
@RestController
@RequestMapping("/api/admin/product-groups")
@RequiredArgsConstructor
public class AdminProductGroupController {

    private final ProductGroupRepository groupRepository;
    private final ProductGroupMemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final MarginService marginService;

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return groupRepository.findAllByOrderByNameAsc().stream().map(this::toMap).toList();
    }

    @PostMapping
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        ProductGroupEntity e = new ProductGroupEntity();
        apply(e, body);
        if (e.getName() == null || e.getName().isBlank()) {
            throw new BusinessException("El nombre del grupo es obligatorio.");
        }
        marginService.invalidateCache();
        return ResponseEntity.ok(toMap(groupRepository.save(e)));
    }

    @PutMapping("/{id}")
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        ProductGroupEntity e = groupRepository.findById(id).orElseThrow(() -> new NotFoundException("Product group"));
        apply(e, body);
        marginService.invalidateCache();
        return ResponseEntity.ok(toMap(groupRepository.save(e)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        memberRepository.deleteByIdGroupId(id);
        groupRepository.deleteById(id);
        marginService.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(@PathVariable UUID id) {
        List<UUID> ids = memberRepository.findProductIdsByGroupId(id);
        return productRepository.findAllById(ids).stream().map(this::productSummary).toList();
    }

    @PostMapping("/{id}/members")
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> addMembers(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        if (groupRepository.findById(id).isEmpty()) {
            throw new NotFoundException("Product group");
        }
        Object raw = body.get("productIds");
        int added = 0;
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                UUID productId = UUID.fromString(o.toString());
                if (!memberRepository.existsByIdGroupIdAndIdProductId(id, productId)
                        && productRepository.existsById(productId)) {
                    memberRepository.save(new ProductGroupMemberEntity(new ProductGroupMemberEntity.Id(id, productId)));
                    added++;
                }
            }
        }
        marginService.invalidateCache();
        return ResponseEntity.ok(Map.of("added", added));
    }

    @DeleteMapping("/{id}/members/{productId}")
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID productId) {
        memberRepository.deleteByIdGroupIdAndIdProductId(id, productId);
        marginService.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    private void apply(ProductGroupEntity e, Map<String, Object> b) {
        if (b.get("name") != null) {
            e.setName(b.get("name").toString().trim());
        }
        if (b.containsKey("description")) {
            e.setDescription(b.get("description") != null && !b.get("description").toString().isBlank()
                    ? b.get("description").toString() : null);
        }
        if (b.get("active") != null) {
            e.setActive(Boolean.parseBoolean(b.get("active").toString()));
        }
    }

    private Map<String, Object> toMap(ProductGroupEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("active", e.isActive());
        m.put("memberCount", memberRepository.countByIdGroupId(e.getId()));
        return m;
    }

    private Map<String, Object> productSummary(ProductEntity p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("title", p.getTitleZh() != null ? p.getTitleZh() : p.getSlug());
        m.put("slug", p.getSlug());
        return m;
    }
}
