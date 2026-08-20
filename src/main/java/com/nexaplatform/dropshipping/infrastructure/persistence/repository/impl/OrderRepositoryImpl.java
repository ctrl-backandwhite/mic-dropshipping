package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.OrderEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link OrderRepository} domain port on
 * top of Spring Data JPA. Owns the persistence-only concerns the domain model
 * abstracts away:
 * <ul>
 *   <li>resolving / creating the shipping &amp; billing {@code AddressEntity} from
 *       the flattened address ids (existing) or the flat snapshot fields (new), and</li>
 *   <li>rebuilding the {@code items} collection, resolving each line's managed
 *       product / variant relations from the flattened ids.</li>
 * </ul>
 * The legacy Spring Data {@code AddressRepository}/{@code ProductRepository}/
 * {@code ProductVariantRepository} are reused as collaborators.
 */
@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderEntityMapper orderEntityMapper;
    private final OrderJpaRepositoryAdapter orderJpaRepositoryAdapter;
    private final AddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    @Override
    public Order save(Order model) {
        CustomerOrderEntity entity = resolveEntity(model);
        applyModel(entity, model);
        CustomerOrderEntity saved = orderJpaRepositoryAdapter.save(entity);
        return orderEntityMapper.toDomain(saved);
    }

    @Override
    public List<Order> findAll() {
        return orderJpaRepositoryAdapter.findAll().stream().map(orderEntityMapper::toDomain).toList();
    }

    @Override
    public Order update(Order model) {
        return this.save(model);
    }

    @Override
    public Order getById(UUID id) {
        return orderJpaRepositoryAdapter.findById(id).map(orderEntityMapper::toDomain).orElse(null);
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return orderJpaRepositoryAdapter.findById(id).map(orderEntityMapper::toDomain);
    }

    @Override
    public Optional<Order> findByOrderNumber(String orderNumber) {
        return orderJpaRepositoryAdapter.findByOrderNumber(orderNumber).map(orderEntityMapper::toDomain);
    }

    @Override
    public Optional<Order> findByTrackingNumber(String trackingNumber) {
        return orderJpaRepositoryAdapter.findByTrackingNumber(trackingNumber).map(orderEntityMapper::toDomain);
    }

    @Override
    public List<Order> findByPartnerAppId(UUID partnerAppId) {
        return orderJpaRepositoryAdapter.findByPartnerAppId(partnerAppId).stream().map(orderEntityMapper::toDomain)
                .toList();
    }

    @Override
    public void delete(UUID id) {
        orderJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return orderJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private CustomerOrderEntity resolveEntity(Order model) {
        if (model.getId() != null) {
            return orderJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Order not found"));
        }
        return new CustomerOrderEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving managed relations. */
    private void applyModel(CustomerOrderEntity entity, Order model) {
        // Campos escalares (incluido el tracking de Cainiao) vía MapStruct; las relaciones gestionadas
        // (direcciones, items) se resuelven abajo porque requieren lookups de repositorio.
        orderEntityMapper.updateEntity(entity, model);

        // Resolve / create the shipping & billing addresses only when the entity
        // does not already carry them (insert path); updates keep the managed ones.
        if (entity.getShippingAddress() == null) {
            entity.setShippingAddress(resolveShipping(model));
        }
        if (entity.getBillingAddress() == null) {
            entity.setBillingAddress(resolveBilling(model));
        }

        // Rebuild the item collection only when the model carries lines (insert path).
        // Status-only updates carry no items and must not wipe the existing ones.
        if (model.getItems() != null && !model.getItems().isEmpty() && entity.getItems().isEmpty()) {
            for (OrderItem itemModel : model.getItems()) {
                entity.getItems().add(buildItem(entity, itemModel));
            }
        }
    }

    /** Persists a fresh shipping address from the flat snapshot fields (mandatory). */
    private AddressEntity resolveShipping(Order model) {
        if (model.getShippingAddressId() != null) {
            return addressRepository.findById(model.getShippingAddressId())
                    .orElseThrow(() -> new NotFoundException("Address not found"));
        }
        return addressRepository.save(AddressEntity.builder().fullName(model.getShippingFullName())
                .phone(model.getShippingPhone()).email(model.getShippingEmail()).line1(model.getShippingLine1())
                .line2(model.getShippingLine2()).city(model.getShippingCity()).state(model.getShippingState())
                .postalCode(model.getShippingPostalCode()).country(model.getShippingCountry()).build());
    }

    /** Persists a fresh billing address from the flat snapshot fields (optional). */
    private AddressEntity resolveBilling(Order model) {
        if (model.getBillingAddressId() != null) {
            return addressRepository.findById(model.getBillingAddressId())
                    .orElseThrow(() -> new NotFoundException("Address not found"));
        }
        if (model.getBillingLine1() == null && model.getBillingFullName() == null) {
            return null;
        }
        return addressRepository.save(AddressEntity.builder().fullName(model.getBillingFullName())
                .phone(model.getBillingPhone()).email(model.getBillingEmail()).line1(model.getBillingLine1())
                .line2(model.getBillingLine2()).city(model.getBillingCity()).state(model.getBillingState())
                .postalCode(model.getBillingPostalCode()).country(model.getBillingCountry()).build());
    }

    /** Builds a managed order line, resolving the product / variant relations from ids. */
    private OrderItemEntity buildItem(CustomerOrderEntity order, OrderItem itemModel) {
        ProductEntity product = productRepository.findById(itemModel.getProductId())
                .orElseThrow(() -> new NotFoundException("Product not found: " + itemModel.getProductId()));
        ProductVariantEntity variant = itemModel.getVariantId() == null
                ? null
                // Carrito obsoleto (variante re-importada con otro ID): código específico + variantId en
                // detail para que el checkout identifique y quite la línea rota, no un 404 genérico.
                : variantRepository.findById(itemModel.getVariantId())
                        .orElseThrow(() -> new NotFoundException("CART_ITEM_UNAVAILABLE",
                                List.of(itemModel.getVariantId().toString())));
        return OrderItemEntity.builder().order(order).product(product).variant(variant)
                .titleSnapshot(itemModel.getTitleSnapshot()).imageUrlSnapshot(itemModel.getImageUrlSnapshot())
                .skuSnapshot(itemModel.getSkuSnapshot()).unitPriceCents(itemModel.getUnitPriceCents())
                .costCents(itemModel.getCostCents()).costCnyCents(itemModel.getCostCnyCents())
                .quantity(itemModel.getQuantity()).lineTotalCents(itemModel.getLineTotalCents())
                // Snapshot de la descripción declarada: si el grupo se aprueba DESPUÉS de cobrar, este
                // pedido tiene que seguir contando por lo que se declaró, no por cómo se agrupe hoy.
                .declaredDescription(itemModel.getDeclaredDescription()).build();
    }
}
