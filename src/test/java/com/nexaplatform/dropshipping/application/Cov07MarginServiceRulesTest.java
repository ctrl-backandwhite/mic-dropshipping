package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryGroupMemberRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PriceRuleRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductGroupMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Precedencia y desempate de las reglas de margen. Que el precio dependa del orden de iteración de la
 * base de datos sería un defecto de cobro: la misma ficha valdría distinto en dos peticiones seguidas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07MarginServiceRulesTest {

    @Mock
    PriceRuleRepository repository;
    @Mock
    ProductGroupMemberRepository groupMemberRepository;
    @Mock
    CategoryGroupMemberRepository categoryGroupMemberRepository;

    @InjectMocks
    MarginService service;

    @AfterEach
    void tearDown() {
        // El canal y el país viven en ThreadLocal: si no se limpian, contaminan el resto de tests del hilo.
        PricingChannelHolder.clear();
        PricingCountryHolder.clear();
    }

    @Test
    void unaReglaDeUnPaisGanaSobreLaGlobalCuandoElPaisCoincide() {
        PriceRuleEntity global = rule(PriceRuleScope.GLOBAL, null, "100");   // cualquier país: 100%
        PriceRuleEntity alemania = rule(PriceRuleScope.GLOBAL, null, "120"); // DE: 120%
        alemania.setCountryCode("DE");
        rules(global, alemania);

        // Comprador en Alemania: gana la regla del país (120%) → 10 × 2,20 = 22,00
        PricingCountryHolder.set("DE");
        assertThat(service.apply(new BigDecimal("10"), null, null).retailUsd()).isEqualByComparingTo("22.0000");

        // Comprador en Francia (sin regla propia) o sin país: cae a la global (100%) → 10 × 2 = 20,00
        PricingCountryHolder.set("FR");
        assertThat(service.apply(new BigDecimal("10"), null, null).retailUsd()).isEqualByComparingTo("20.0000");
        PricingCountryHolder.clear();
        assertThat(service.apply(new BigDecimal("10"), null, null).retailUsd()).isEqualByComparingTo("20.0000");
    }

    /* ==================== precedencia entre ámbitos ==================== */

    @Test
    void laVarianteGanaAlProductoYElProductoAlProveedor() {
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        rules(rule(PriceRuleScope.SUPPLIER, supplierId, "10"), rule(PriceRuleScope.PRODUCT, productId, "20"),
                rule(PriceRuleScope.VARIANT, variantId, "30"));
        ProductEntity product = product(productId, supplierId, null);

        assertThat(resolvedScope(product, variant(variantId))).isEqualTo(PriceRuleScope.VARIANT);
    }

    @Test
    void elProveedorGanaALaCategoriaYLaCategoriaAlGlobal() {
        UUID supplierId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        rules(rule(PriceRuleScope.GLOBAL, null, "10"), rule(PriceRuleScope.CATEGORY, categoryId, "20"),
                rule(PriceRuleScope.SUPPLIER, supplierId, "30"));
        ProductEntity product = product(UUID.randomUUID(), supplierId, categoryId);

        assertThat(resolvedScope(product, null)).isEqualTo(PriceRuleScope.SUPPLIER);
    }

    @Test
    void elGrupoDeProductosSeSituaEntreElProductoYElProveedor() {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        rules(rule(PriceRuleScope.SUPPLIER, supplierId, "10"), rule(PriceRuleScope.PRODUCT_GROUP, groupId, "20"));
        when(groupMemberRepository.findGroupIdsByProductId(productId)).thenReturn(List.of(groupId));

        assertThat(resolvedScope(product(productId, supplierId, null), null))
                .isEqualTo(PriceRuleScope.PRODUCT_GROUP);
    }

    @Test
    void unProductoQueNoPerteneceAlGrupoCaeAlAmbitoMenosEspecifico() {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        rules(rule(PriceRuleScope.SUPPLIER, supplierId, "10"),
                rule(PriceRuleScope.PRODUCT_GROUP, UUID.randomUUID(), "20"));
        when(groupMemberRepository.findGroupIdsByProductId(productId)).thenReturn(List.of());

        assertThat(resolvedScope(product(productId, supplierId, null), null)).isEqualTo(PriceRuleScope.SUPPLIER);
    }

    @Test
    void elGrupoDeCategoriasSeSituaEntreLaCategoriaYElGlobal() {
        UUID categoryId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        rules(rule(PriceRuleScope.GLOBAL, null, "10"), rule(PriceRuleScope.CATEGORY_GROUP, groupId, "20"));
        when(categoryGroupMemberRepository.findGroupIdsByCategoryId(categoryId)).thenReturn(List.of(groupId));

        assertThat(resolvedScope(product(UUID.randomUUID(), null, categoryId), null))
                .isEqualTo(PriceRuleScope.CATEGORY_GROUP);
    }

    @Test
    void unaReglaDesactivadaNoAplicaAunqueSeaLaMasEspecifica() {
        UUID productId = UUID.randomUUID();
        PriceRuleEntity apagada = rule(PriceRuleScope.PRODUCT, productId, "999");
        apagada.setActive(false);
        rules(apagada, rule(PriceRuleScope.GLOBAL, null, "10"));

        assertThat(resolvedScope(product(productId, null, null), null)).isEqualTo(PriceRuleScope.GLOBAL);
    }

    @Test
    void sinNingunaReglaAplicableElPrecioEsElCoste() {
        rules(rule(PriceRuleScope.PRODUCT, UUID.randomUUID(), "100"));

        MarginService.PriceWithMargin result = service.apply(new BigDecimal("10"), null, null);

        assertThat(result.retailUsd()).isEqualByComparingTo("10");
        assertThat(result.appliedRule()).isNull();
        assertThat(result.appliedPercentage()).isEqualByComparingTo("0");
    }

    @Test
    void elCanalDeIntegracionUsaSuPropiaRegla() {
        PriceRuleEntity storefront = rule(PriceRuleScope.GLOBAL, null, "150");
        PriceRuleEntity integration = rule(PriceRuleScope.GLOBAL, null, "75");
        integration.setChannel(PriceRuleChannel.INTEGRATION);
        rules(storefront, integration);
        PricingChannelHolder.set(PriceRuleChannel.INTEGRATION);

        // Conviven el margen del escaparate y el de integración sin pisarse.
        assertThat(service.apply(new BigDecimal("10"), null, null).retailUsd()).isEqualByComparingTo("17.5000");
    }

    /* ==================== desempate dentro del mismo ámbito ==================== */

    @Test
    void entreReglasDelMismoAmbitoGanaElRangoDeCosteMasEstrecho() {
        UUID productId = UUID.randomUUID();
        PriceRuleEntity ancha = rule(PriceRuleScope.PRODUCT, productId, "10", null, null);
        PriceRuleEntity mediaAbierta = rule(PriceRuleScope.PRODUCT, productId, "20", "1", null);
        PriceRuleEntity estrecha = rule(PriceRuleScope.PRODUCT, productId, "30", "5", "15");
        rules(ancha, mediaAbierta, estrecha);

        assertThat(resolvedMargin(product(productId, null, null))).isEqualByComparingTo("30");
    }

    @Test
    void unRangoAbiertoPorUnLadoEsMenosEspecificoQueUnoCerrado() {
        UUID productId = UUID.randomUUID();
        rules(rule(PriceRuleScope.PRODUCT, productId, "10", null, "1000000000"),
                rule(PriceRuleScope.PRODUCT, productId, "20", null, null));

        // Acotar un extremo, por alto que sea el tope, es más concreto que "cualquier coste".
        assertThat(resolvedMargin(product(productId, null, null))).isEqualByComparingTo("10");
    }

    @Test
    void aIgualdadDeRangoGanaLaPosicionMasBaja() {
        UUID productId = UUID.randomUUID();
        PriceRuleEntity segunda = rule(PriceRuleScope.PRODUCT, productId, "10", "1", "100");
        segunda.setPosition(5);
        PriceRuleEntity primera = rule(PriceRuleScope.PRODUCT, productId, "20", "1", "100");
        primera.setPosition(1);
        rules(segunda, primera);

        assertThat(resolvedMargin(product(productId, null, null))).isEqualByComparingTo("20");
    }

    @Test
    void aIgualdadDePosicionGanaLaReglaCreadaMasRecientemente() {
        UUID productId = UUID.randomUUID();
        PriceRuleEntity vieja = rule(PriceRuleScope.PRODUCT, productId, "10", "1", "100");
        vieja.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        PriceRuleEntity nueva = rule(PriceRuleScope.PRODUCT, productId, "20", "1", "100");
        nueva.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        rules(vieja, nueva);

        assertThat(resolvedMargin(product(productId, null, null))).isEqualByComparingTo("20");
    }

    @Test
    void laResolucionEsEstableAunqueLasReglasLleguenEnOtroOrden() {
        UUID productId = UUID.randomUUID();
        PriceRuleEntity a = rule(PriceRuleScope.PRODUCT, productId, "10", "1", "100");
        a.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        PriceRuleEntity b = rule(PriceRuleScope.PRODUCT, productId, "20", "1", "100");
        b.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        when(repository.findByActiveTrueOrderByPositionAsc()).thenReturn(List.of(a, b));
        BigDecimal primera = resolvedMargin(product(productId, null, null));
        service.invalidateCache();
        when(repository.findByActiveTrueOrderByPositionAsc()).thenReturn(List.of(b, a));
        BigDecimal segunda = resolvedMargin(product(productId, null, null));

        // Sin el desempate por id, el mismo producto cambiaría de precio según cómo ordenara la BD.
        assertThat(primera).isEqualByComparingTo(segunda).isEqualByComparingTo("10");
    }

    /* ==================== rangos de coste ==================== */

    @Test
    void elCosteJustoEnLosExtremosDelRangoSiEntra() {
        rules(rule(PriceRuleScope.GLOBAL, null, "100", "5", "8"));

        assertThat(service.resolve(null, null, new BigDecimal("5"))).isPresent();
        assertThat(service.resolve(null, null, new BigDecimal("8"))).isPresent();
        assertThat(service.resolve(null, null, new BigDecimal("4.99"))).isEmpty();
        assertThat(service.resolve(null, null, new BigDecimal("8.01"))).isEmpty();
    }

    @Test
    void unRangoSoloConMinimoNoAcotaPorArriba() {
        rules(rule(PriceRuleScope.GLOBAL, null, "100", "5", null));

        assertThat(service.resolve(null, null, new BigDecimal("1000000"))).isPresent();
        assertThat(service.resolve(null, null, new BigDecimal("1"))).isEmpty();
    }

    /* ==================== caché ==================== */

    @Test
    void laCacheEvitaConsultarLasReglasEnCadaPrecio() {
        rules(rule(PriceRuleScope.GLOBAL, null, "100"));

        service.warm();
        service.resolve(null, null, new BigDecimal("10"));
        service.resolve(null, null, new BigDecimal("10"));

        // El cálculo del precio está en el camino caliente: una consulta por producto lo hundiría.
        verify(repository, times(1)).findByActiveTrueOrderByPositionAsc();
    }

    @Test
    void invalidarLaCacheFuerzaAVolverALeerLasReglas() {
        rules(rule(PriceRuleScope.GLOBAL, null, "100"));
        service.warm();

        service.invalidateCache();
        service.resolve(null, null, new BigDecimal("10"));

        verify(repository, times(2)).findByActiveTrueOrderByPositionAsc();
    }

    @Test
    void guardarYBorrarUnaReglaInvalidanLaCacheAlInstante() {
        PriceRuleEntity regla = rule(PriceRuleScope.GLOBAL, null, "100");
        when(repository.findByActiveTrueOrderByPositionAsc()).thenReturn(List.of(regla));
        when(repository.save(regla)).thenReturn(regla);
        service.warm();

        service.save(regla);
        service.listAll();
        service.delete(UUID.randomUUID());
        service.listAll();

        // Un cambio de margen en admin tiene que verse ya: warm + tras guardar + tras borrar = 3.
        verify(repository, times(3)).findByActiveTrueOrderByPositionAsc();
    }

    @Test
    void elListadoDeReglasEsUnaCopiaInmutable() {
        rules(rule(PriceRuleScope.GLOBAL, null, "100"));

        List<PriceRuleEntity> listed = service.listAll();

        assertThat(listed).hasSize(1);
        // La regla se construye fuera de la lambda: dentro solo debe quedar la llamada que se espera que falle.
        PriceRuleEntity otra = rule(PriceRuleScope.GLOBAL, null, "1");
        assertThatThrownBy(() -> listed.add(otra)).isInstanceOf(UnsupportedOperationException.class);
    }

    /* ==================== helpers ==================== */

    private void rules(PriceRuleEntity... rs) {
        when(repository.findByActiveTrueOrderByPositionAsc()).thenReturn(List.of(rs));
    }

    private PriceRuleScope resolvedScope(ProductEntity product, ProductVariantEntity variant) {
        Optional<PriceRuleEntity> resolved = service.resolve(product, variant, new BigDecimal("10"));
        assertThat(resolved).isPresent();
        return resolved.get().getScope();
    }

    private BigDecimal resolvedMargin(ProductEntity product) {
        Optional<PriceRuleEntity> resolved = service.resolve(product, null, new BigDecimal("10"));
        assertThat(resolved).isPresent();
        return resolved.get().getMarginValue();
    }

    private static PriceRuleEntity rule(PriceRuleScope scope, UUID scopeId, String margin) {
        return rule(scope, scopeId, margin, null, null);
    }

    private static PriceRuleEntity rule(PriceRuleScope scope, UUID scopeId, String margin, String min, String max) {
        PriceRuleEntity rule = PriceRuleEntity.builder().scope(scope).scopeId(scopeId)
                .channel(PriceRuleChannel.STOREFRONT).marginType(MarginType.PERCENTAGE)
                .marginValue(new BigDecimal(margin)).active(true)
                .minCostUsd(min == null ? null : new BigDecimal(min))
                .maxCostUsd(max == null ? null : new BigDecimal(max)).build();
        rule.setId(UUID.randomUUID());
        return rule;
    }

    private static ProductEntity product(UUID productId, UUID supplierId, UUID categoryId) {
        ProductEntity product = ProductEntity.builder().build();
        product.setId(productId);
        if (supplierId != null) {
            SupplierEntity supplier = new SupplierEntity();
            supplier.setId(supplierId);
            product.setSupplier(supplier);
        }
        if (categoryId != null) {
            CategoryEntity category = new CategoryEntity();
            category.setId(categoryId);
            product.setCategory(category);
        }
        return product;
    }

    private static ProductVariantEntity variant(UUID variantId) {
        ProductVariantEntity variant = ProductVariantEntity.builder().build();
        variant.setId(variantId);
        return variant;
    }
}
