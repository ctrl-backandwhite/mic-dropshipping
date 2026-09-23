package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTagEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductSpecificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Consultas de la ficha de producto del escaparate: relacionados, especificaciones, atributos y
 * etiquetas.
 *
 * <p>Estaban resueltas dentro del controlador, que además hablaba directamente con cuatro repositorios.
 * Todas encierran una REGLA —qué se considera un producto relacionado, en qué orden se buscan los idiomas
 * de una especificación, cómo se fusionan los atributos neutrales con los traducidos— y esas reglas no son
 * asunto de la capa HTTP.
 */
@Service
@RequiredArgsConstructor
public class ProductDetailQueryService {

    /** Tope de productos entre los que se eligen los relacionados. */
    private static final int RELATED_CANDIDATE_POOL = 200;

    private final ProductRepository productRepository;
    private final ProductSpecificationRepository specRepository;
    private final ProductAttributeRepository attributeRepository;
    private final ProductTagRepository tagRepository;

    /**
     * Productos relacionados: los de la MISMA categoría, activos, ordenados por tendencia.
     *
     * <p>Se consulta por categoría con un tope, en vez de traer el catálogo entero y filtrarlo en
     * memoria: con casi 4.000 productos, resolver "dame 8 relacionados" cargándolos todos es un coste
     * absurdo en una página que ve cada visitante.
     */
    @Transactional(readOnly = true)
    public List<ProductEntity> relatedProducts(UUID id, int limit) {
        ProductEntity product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product"));
        UUID categoryId = product.getCategory() != null ? product.getCategory().getId() : null;
        List<ProductEntity> candidates = categoryId != null
                ? productRepository.findByCategoryIdAndStatus(categoryId, ProductStatus.ACTIVE,
                        PageRequest.of(0, RELATED_CANDIDATE_POOL)).getContent()
                : productRepository.findVisibleByStatus(ProductStatus.ACTIVE, PageRequest.of(0, RELATED_CANDIDATE_POOL))
                        .getContent();
        return candidates.stream().filter(x -> !x.getId().equals(id))
                .sorted(Comparator.comparing(ProductDetailQueryService::trendScore).reversed()).limit(limit).toList();
    }

    private static BigDecimal trendScore(ProductEntity p) {
        return p.getTrendScore() == null ? BigDecimal.ZERO : p.getTrendScore();
    }

    /**
     * Especificaciones en el idioma pedido. Cada posición se sirve en el idioma solicitado cuando esa
     * fila existe; si no, se completa con la del idioma base (español) para que la ficha técnica NUNCA
     * salga a medias (un idioma parcialmente traducido ya no deja huecos). Como último recurso —producto
     * sin traducir— caen a las filas sin idioma.
     *
     * <p>Antes la cascada devolvía el idioma completo o, si estaba vacío, inglés o las neutrales: un
     * idioma con solo unas filas traducidas dejaba la ficha incompleta y el escaparate la "rellenaba"
     * mezclando con atributos en español. Ahora el escaparate recibe siempre la ficha completa en un
     * único idioma coherente.
     */
    @Transactional(readOnly = true)
    public List<ProductSpecificationEntity> specifications(UUID id, String lang) {
        String pedido = (lang == null || lang.isBlank()) ? "es" : lang.trim().toLowerCase(Locale.ROOT);
        List<ProductSpecificationEntity> todas = specRepository.findByProduct_IdOrderByPositionAsc(id);
        if (todas.isEmpty()) {
            return todas;
        }
        // Mejor fila por posición: la del idioma pedido si existe; si no, la del base (es).
        Map<Integer, ProductSpecificationEntity> porPosicion = new LinkedHashMap<>();
        Map<Integer, ProductSpecificationEntity> base = new LinkedHashMap<>();
        for (ProductSpecificationEntity s : todas) {
            String loc = s.getLocale() == null ? "" : s.getLocale().trim().toLowerCase(Locale.ROOT);
            if (pedido.equals(loc)) {
                porPosicion.putIfAbsent(s.getPosition(), s);
            } else if ("es".equals(loc)) {
                base.putIfAbsent(s.getPosition(), s);
            }
        }
        // Si el idioma pedido no tiene NINGUNA fila, caemos primero a inglés (como antes) y luego al
        // español, para que la ficha no quede vacía en un idioma aún sin traducir.
        if (porPosicion.isEmpty() && !"en".equals(pedido) && !"es".equals(pedido)) {
            for (ProductSpecificationEntity s : todas) {
                String loc = s.getLocale() == null ? "" : s.getLocale().trim().toLowerCase(Locale.ROOT);
                if ("en".equals(loc)) {
                    porPosicion.putIfAbsent(s.getPosition(), s);
                }
            }
        }
        // Completamos los huecos (posiciones sin fila en el idioma pedido) con el idioma base.
        for (Map.Entry<Integer, ProductSpecificationEntity> e : base.entrySet()) {
            porPosicion.putIfAbsent(e.getKey(), e.getValue());
        }
        // Último recurso: filas sin idioma.
        if (porPosicion.isEmpty()) {
            for (ProductSpecificationEntity s : todas) {
                if (s.getLocale() == null || s.getLocale().isBlank()) {
                    porPosicion.putIfAbsent(s.getPosition(), s);
                }
            }
        }
        // Resultado ordenado por posición (el mapa ya lo mantiene: se inserta en orden).
        return new ArrayList<>(porPosicion.values());
    }

    @Transactional(readOnly = true)
    public Map<String, String> attributes(UUID id, String lang) {
        List<ProductAttributeEntity> all = attributeRepository.findByProduct_Id(id);
        LinkedHashMap<String, String> byKey = new LinkedHashMap<>();
        for (ProductAttributeEntity a : all) {
            if (a.getLocale() == null) {
                byKey.putIfAbsent(a.getAttrKey(), a.getAttrValue());
            }
        }
        if (lang != null) {
            for (ProductAttributeEntity a : all) {
                if (lang.equalsIgnoreCase(a.getLocale())) {
                    byKey.put(a.getAttrKey(), a.getAttrValue());
                }
            }
        }
        return byKey;
    }

    @Transactional(readOnly = true)
    public List<String> tags(UUID id) {
        return tagRepository.findByProduct_Id(id).stream().map(ProductTagEntity::getTag).toList();
    }
}
