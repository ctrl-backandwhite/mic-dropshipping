package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.domain.model.ProductImage;
import com.nexaplatform.dropshipping.domain.model.ProductPriceTier;
import com.nexaplatform.dropshipping.domain.model.ProductTranslation;
import com.nexaplatform.dropshipping.domain.model.ProductVariant;
import com.nexaplatform.dropshipping.domain.model.VariantOption;
import com.nexaplatform.dropshipping.domain.model.VariantValue;
import com.nexaplatform.dropshipping.domain.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link ProductRepository} domain port on
 * top of Spring Data JPA. Owns the persistence-only concerns the domain model
 * abstracts away: resolving the managed {@code supplier}/{@code category} from the
 * flattened ids and rebuilding the nested image/variant/option/translation
 * collections from the model on save. Price tiers (their own table) are
 * synchronised separately. Read finders mirror the legacy queries one-to-one.
 */
@Repository
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductEntityMapper productEntityMapper;
    private final ProductJpaRepositoryAdapter productJpaRepositoryAdapter;
    private final SupplierJpaRepositoryAdapter supplierJpaRepositoryAdapter;
    private final CategoryJpaRepositoryAdapter categoryJpaRepositoryAdapter;
    private final ProductPriceTierRepository priceTierRepository;

    @Override
    public Product save(Product model) {
        ProductEntity entity = resolveEntity(model);
        applyModel(entity, model);
        ProductEntity saved = productJpaRepositoryAdapter.save(entity);
        syncPriceTiers(saved, model.getPriceTiers());
        return toDomainWithTiers(saved);
    }

    @Override
    public Product update(Product model) {
        return this.save(model);
    }

    @Override
    public Product getById(UUID id) {
        return productJpaRepositoryAdapter.findById(id).map(this::toDomainWithTiers).orElse(null);
    }

    @Override
    public boolean existsById(UUID id) {
        return productJpaRepositoryAdapter.existsById(id);
    }

    @Override
    public void delete(UUID id) {
        productJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public List<Product> findAll() {
        return productEntityMapper.toDomainList(productJpaRepositoryAdapter.findAll());
    }

    @Override
    public Optional<Product> findBySlug(String slug) {
        return productJpaRepositoryAdapter.findBySlug(slug).map(productEntityMapper::toDomain);
    }

    @Override
    public Optional<Product> findBySourceAndExternalId(String source, String externalId) {
        return productJpaRepositoryAdapter.findBySourceAndExternalId(source, externalId)
                .map(productEntityMapper::toDomain);
    }

    @Override
    public Optional<Product> findFirstByExternalId(String externalId) {
        return productJpaRepositoryAdapter.findFirstByExternalId(externalId).map(productEntityMapper::toDomain);
    }

    @Override
    public Optional<Product> findWithDetailsBySlug(String slug) {
        return productJpaRepositoryAdapter.findWithDetailsBySlug(slug).map(this::toDomainWithTiers);
    }

    @Override
    public Optional<Product> findWithDetailsById(UUID id) {
        return productJpaRepositoryAdapter.findWithDetailsById(id).map(this::toDomainWithTiers);
    }

    @Override
    public Page<Product> findByStatus(ProductStatus status, Pageable pageable) {
        return productJpaRepositoryAdapter.findByStatus(status, pageable).map(productEntityMapper::toDomain);
    }

    @Override
    public Page<Product> findAll(Pageable pageable) {
        return productJpaRepositoryAdapter.findAll(pageable).map(productEntityMapper::toDomain);
    }

    @Override
    public Page<Product> findTopByTrendScore(ProductStatus status, Pageable pageable) {
        return productJpaRepositoryAdapter.findTopByTrendScore(status, pageable).map(productEntityMapper::toDomain);
    }

    @Override
    public Page<Product> findByCategoryOrderByTrend(UUID categoryId, ProductStatus status, Pageable pageable) {
        return productJpaRepositoryAdapter.findByCategoryOrderByTrend(categoryId, status, pageable)
                .map(productEntityMapper::toDomain);
    }

    @Override
    public List<Product> findAllById(List<UUID> ids) {
        return productEntityMapper.toDomainList(productJpaRepositoryAdapter.findAllById(ids));
    }

    /* ------------------ persistence-only helpers ------------------ */

    /** Maps the entity to domain and attaches the price-tier ladder from its own table. */
    private Product toDomainWithTiers(ProductEntity entity) {
        Product product = productEntityMapper.toDomain(entity);
        if (entity.getId() != null) {
            product.setPriceTiers(productEntityMapper
                    .toPriceTierDomainList(priceTierRepository.findByProductIdOrderByMinQtyAsc(entity.getId())));
        }
        return product;
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private ProductEntity resolveEntity(Product model) {
        if (model.getId() != null) {
            return productJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Product"));
        }
        return new ProductEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving relations and nested collections. */
    private void applyModel(ProductEntity entity, Product model) {
        // Campos escalares simples vía MapStruct; las relaciones gestionadas
        // (supplier, category) y las colecciones anidadas (images, variants,
        // variantOptions, translations) se resuelven abajo porque requieren
        // lookups de repositorio o reconstrucción de sub-entidades.
        productEntityMapper.updateEntity(entity, model);
        // Un id que no existe es un error de datos, no «sin relación»: con orElse(null) el producto se
        // guardaba sin proveedor ni categoría y quedaba fuera del escaparate sin que nadie se enterara.
        entity.setSupplier(model.getSupplierId() == null
                ? null
                : supplierJpaRepositoryAdapter.findById(model.getSupplierId())
                        .orElseThrow(() -> new NotFoundException("Supplier")));
        entity.setCategory(model.getCategoryId() == null
                ? null
                : categoryJpaRepositoryAdapter.findById(model.getCategoryId())
                        .orElseThrow(() -> new NotFoundException("Category")));
        rebuildImages(entity, model);
        rebuildVariantOptions(entity, model);
        rebuildVariants(entity, model);
        rebuildTranslations(entity, model);
    }

    private void rebuildImages(ProductEntity entity, Product model) {
        entity.getImages().clear();
        if (model.getImages() == null) {
            return;
        }
        for (ProductImage img : model.getImages()) {
            entity.getImages()
                    .add(ProductImageEntity.builder().product(entity).position(img.getPosition())
                            .role(img.getRole() != null ? img.getRole() : "GALLERY").sourceUrl(img.getSourceUrl())
                            .cdnUrl(img.getCdnUrl())
                            .mirrorStatus(img.getMirrorStatus() != null ? img.getMirrorStatus() : MirrorStatus.PENDING)
                            .build());
        }
    }

    private void rebuildVariantOptions(ProductEntity entity, Product model) {
        entity.getVariantOptions().clear();
        if (model.getVariantOptions() == null) {
            return;
        }
        for (VariantOption opt : model.getVariantOptions()) {
            VariantOptionEntity oe = VariantOptionEntity.builder().product(entity).nameZh(opt.getNameZh())
                    .name(opt.getName()).position(opt.getPosition()).build();
            if (opt.getValues() != null) {
                for (VariantValue v : opt.getValues()) {
                    oe.getValues()
                            .add(VariantValueEntity.builder().option(oe).valueZh(v.getValueZh()).value(v.getValue())
                                    .imageSourceUrl(v.getImageSourceUrl()).imageCdnUrl(v.getImageCdnUrl())
                                    .position(v.getPosition()).build());
                }
            }
            entity.getVariantOptions().add(oe);
        }
    }

    private void rebuildVariants(ProductEntity entity, Product model) {
        entity.getVariants().clear();
        if (model.getVariants() == null) {
            return;
        }
        for (ProductVariant v : model.getVariants()) {
            entity.getVariants()
                    .add(ProductVariantEntity.builder().product(entity).externalId(v.getExternalId()).sku(v.getSku())
                            .title(v.getTitle()).price(v.getPrice()).stock(v.getStock())
                            .imageSourceUrl(v.getImageSourceUrl()).imageCdnUrl(v.getImageCdnUrl())
                            .options(v.getOptions()).active(v.isActive()).build());
        }
    }

    private void rebuildTranslations(ProductEntity entity, Product model) {
        entity.getTranslations().clear();
        if (model.getTranslations() == null) {
            return;
        }
        for (ProductTranslation tr : model.getTranslations()) {
            entity.getTranslations()
                    .add(ProductTranslationEntity.builder().product(entity).language(tr.getLanguage())
                            .title(tr.getTitle()).shortDescription(tr.getShortDescription())
                            .description(tr.getDescription()).metaTitle(tr.getMetaTitle())
                            .metaDescription(tr.getMetaDescription()).provider(tr.getProvider()).build());
        }
    }

    /** Replaces the price-tier ladder (own table) to match the model on save. */
    private void syncPriceTiers(ProductEntity entity, List<ProductPriceTier> tiers) {
        if (tiers == null) {
            return;
        }
        priceTierRepository.findByProductIdOrderByMinQtyAsc(entity.getId()).forEach(priceTierRepository::delete);
        for (ProductPriceTier t : tiers) {
            priceTierRepository.save(ProductPriceTierEntity.builder().product(entity).minQty(t.getMinQty())
                    .maxQty(t.getMaxQty()).unitPrice(t.getUnitPrice())
                    .currency(t.getCurrency() != null ? t.getCurrency() : "CNY").build());
        }
    }
}
