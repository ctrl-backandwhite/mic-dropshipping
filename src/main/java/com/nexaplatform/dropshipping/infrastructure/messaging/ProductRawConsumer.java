package com.nexaplatform.dropshipping.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantValue;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes raw products produced by the Python crawler and persists them via CatalogService.
 * The crawler ships pydantic JSON with snake_case keys; we map to the Java DTOs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRawConsumer {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String EXTERNAL_ID = "external_id";
    private static final String POSITION = "position";
    private static final String SOURCE = "source";

    private final CatalogUseCase catalogService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = NexaTopics.PRODUCT_RAW, groupId = "nexadrop-backend-raw")
    public void onProductRaw(Object payload) {
        try {
            JsonNode root = objectMapper.valueToTree(payload);
            if (root == null || root.isNull()) {
                // valueToTree(null) devuelve null: un mensaje vacío en el topic tumbaba al consumidor con
                // NullPointerException en la primera lectura, y el offset no avanzaba.
                log.warn("Raw product message with empty payload, skipped");
                return;
            }
            log.info("Received raw product: {}/{}", textOrNull(root, SOURCE), textOrNull(root, EXTERNAL_ID));

            UUID supplierId = null;
            JsonNode supplierNode = root.get("supplier");
            if (supplierNode != null && !supplierNode.isNull()) {
                SupplierEntity supplier = catalogService
                        .upsertSupplier(new IngestSupplierRequest(textOrNull(supplierNode, SOURCE),
                                textOrNull(supplierNode, EXTERNAL_ID), textOrNull(supplierNode, "name"),
                                textOrNull(supplierNode, "name_zh"), textOrNull(supplierNode, "country"),
                                textOrNull(supplierNode, "city"), bdOrNull(supplierNode, "rating"),
                                intOrNull(supplierNode, "years_active"), boolOrFalse(supplierNode, "verified"),
                                boolOrFalse(supplierNode, "trust_pass"), textOrNull(supplierNode, "profile_url")));
                supplierId = supplier.getId();
            }

            IngestProductRequest req = new IngestProductRequest(textOrNull(root, SOURCE),
                    textOrNull(root, EXTERNAL_ID), textOrNull(root, "title_zh"),
                    textOrNull(root, "short_description_zh"), textOrNull(root, "description_zh"),
                    textOrNull(root, "brand"), intOrNull(root, "moq"), bdOrNull(root, "base_price"),
                    textOrNull(root, "currency"), intOrNull(root, "weight_grams"), intOrNull(root, "monthly_sales"),
                    bdOrNull(root, "repurchase_rate"), bdOrNull(root, "rating"), intOrNull(root, "review_count"),
                    textOrNull(root, "source_url"), supplierId, null, mapImages(root.get("images")),
                    mapOptions(root.get("options")), mapVariants(root.get("variants")),
                    mapPriceTiers(root.get("price_tiers")));

            catalogService.upsertProduct(req);
        } catch (Exception e) {
            log.error("Failed to ingest raw product: {}", e.getMessage(), e);
        }
    }

    private List<IngestImage> mapImages(JsonNode arr) {
        List<IngestImage> out = new ArrayList<>();
        if (arr == null || !arr.isArray())
            return out;
        for (JsonNode n : arr) {
            String url = textOrNull(n, "source_url");
            if (url == null || url.isBlank())
                continue;
            out.add(new IngestImage(url, intOrZero(n, POSITION), textOrNull(n, "role")));
        }
        return out;
    }

    private List<IngestVariantOption> mapOptions(JsonNode arr) {
        List<IngestVariantOption> out = new ArrayList<>();
        if (arr == null || !arr.isArray())
            return out;
        for (JsonNode n : arr) {
            List<IngestVariantValue> values = new ArrayList<>();
            JsonNode vals = n.get("values");
            if (vals != null && vals.isArray()) {
                for (JsonNode v : vals) {
                    values.add(new IngestVariantValue(textOrNull(v, "value_zh"), intOrZero(v, POSITION),
                            textOrNull(v, "image_source_url")));
                }
            }
            out.add(new IngestVariantOption(textOrNull(n, "name_zh"), intOrZero(n, POSITION), values));
        }
        return out;
    }

    private List<IngestVariant> mapVariants(JsonNode arr) {
        List<IngestVariant> out = new ArrayList<>();
        if (arr == null || !arr.isArray())
            return out;
        for (JsonNode n : arr) {
            Map<String, String> opts = new HashMap<>();
            JsonNode optsNode = n.get("options");
            if (optsNode != null && optsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = optsNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    opts.put(e.getKey(), e.getValue().asText());
                }
            }
            out.add(new IngestVariant(textOrNull(n, EXTERNAL_ID), textOrNull(n, "sku"), textOrNull(n, "title"),
                    bdOrNull(n, "price"), intOrNull(n, "stock"), textOrNull(n, "image_source_url"), opts));
        }
        return out;
    }

    private List<IngestPriceTier> mapPriceTiers(JsonNode arr) {
        List<IngestPriceTier> out = new ArrayList<>();
        if (arr == null || !arr.isArray())
            return out;
        for (JsonNode n : arr) {
            BigDecimal price = bdOrNull(n, "unit_price");
            if (price == null)
                continue;
            out.add(new IngestPriceTier(intOrZero(n, "min_qty"), intOrNull(n, "max_qty"), price,
                    textOrNull(n, "currency")));
        }
        return out;
    }

    /* ---------- small JSON helpers ---------- */

    private static String textOrNull(JsonNode n, String field) {
        if (n == null)
            return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        if (n == null)
            return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static int intOrZero(JsonNode n, String field) {
        Integer i = intOrNull(n, field);
        return i == null ? 0 : i;
    }

    private static BigDecimal bdOrNull(JsonNode n, String field) {
        if (n == null)
            return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : new BigDecimal(v.asText());
    }

    private static boolean boolOrFalse(JsonNode n, String field) {
        if (n == null)
            return false;
        JsonNode v = n.get(field);
        return v != null && !v.isNull() && v.asBoolean(false);
    }
}
