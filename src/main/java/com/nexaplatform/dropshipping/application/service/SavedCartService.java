package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.SavedCartItemDto;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SavedCartItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SavedCartItemJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * "Guardar para más tarde" ligado al usuario. Reglas:
 * <ul>
 *   <li>Guardar una línea ya presente SUMA cantidades (igual que el carrito local) y refresca el
 *       snapshot (precio/título/imagen) al más reciente.</li>
 *   <li>{@link #merge} sube y fusiona una lista completa: lo que usa el login para subir lo que un
 *       invitado guardó en local.</li>
 *   <li>Solo se guardan productos que existen; una referencia inexistente lanza {@link NotFoundException}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedCartService {

    /** Tope duro de unidades por línea, coherente con la validación del checkout. */
    private static final int MAX_QTY = 100_000;

    private final SavedCartItemJpaRepository repo;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<SavedCartItemDto> list(UUID userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(SavedCartItemDto::fromEntity).toList();
    }

    @Transactional
    public List<SavedCartItemDto> upsert(UUID userId, SavedCartItemDto dto) {
        save(userId, dto);
        return list(userId);
    }

    @Transactional
    public List<SavedCartItemDto> merge(UUID userId, List<SavedCartItemDto> items) {
        if (items != null) {
            for (SavedCartItemDto dto : items) {
                if (dto != null) {
                    save(userId, dto);
                }
            }
        }
        return list(userId);
    }

    @Transactional
    public List<SavedCartItemDto> remove(UUID userId, UUID productId, UUID variantId) {
        repo.deleteByUserIdAndProductIdAndVariantId(userId, productId, variantId);
        return list(userId);
    }

    @Transactional(readOnly = true)
    public long count(UUID userId) {
        return repo.countByUserId(userId);
    }

    private void save(UUID userId, SavedCartItemDto dto) {
        if (!productRepository.existsById(dto.productId())) {
            throw new NotFoundException("Product");
        }
        int qty = Math.max(1, dto.quantity());
        SavedCartItemEntity entity = repo.findByUserIdAndProductIdAndVariantId(userId, dto.productId(), dto.variantId())
                .orElseGet(() -> SavedCartItemEntity.builder().userId(userId).productId(dto.productId())
                        .variantId(dto.variantId()).quantity(0).build());
        entity.setQuantity(Math.min(MAX_QTY, entity.getQuantity() + qty));
        applySnapshot(entity, dto);
        repo.save(entity);
    }

    /** Refresca los datos "de pintado" (precio/título/imagen/variante) al valor más reciente recibido. */
    private void applySnapshot(SavedCartItemEntity entity, SavedCartItemDto dto) {
        entity.setSku(dto.sku());
        entity.setSlug(dto.slug());
        entity.setTitle(dto.title());
        entity.setImageUrl(dto.image());
        entity.setVariantLabel(dto.variantLabel());
        entity.setUnitPriceSource(dto.unitPriceSource());
        entity.setSourceCurrency(dto.sourceCurrency());
        entity.setMoq(dto.moq());
        entity.setUnitPriceDisplay(dto.unitPriceDisplay());
        entity.setDisplayCurrency(dto.displayCurrency());
        entity.setDisplaySymbol(dto.displaySymbol());
    }
}
