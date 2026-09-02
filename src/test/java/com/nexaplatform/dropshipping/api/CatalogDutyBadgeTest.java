package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.application.service.CatalogDutyBadgeService;
import com.nexaplatform.dropshipping.application.service.CatalogDutyBadgeService.DutyBadge;
import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.ProductSubsidyService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El distintivo de arancel del catálogo: «✓ sin arancel adicional» o «+3,00 €».
 *
 * <p>Es una <b>promesa de precio</b> hecha antes del checkout, así que tiene que calcularse con el mismo
 * repartidor de bultos y el mismo contador de líneas que después cobran. El derecho se cobra por línea de
 * declaración <b>y por bulto</b>: comparar grupos en el navegador daría un ✓ falso en cuanto el carrito
 * se parta en dos, y esos 3 EUR los pondría el comercio al despachar.
 */
class CatalogDutyBadgeTest {

    private static final UUID EN_CARRITO = UUID.randomUUID();
    private static final UUID MISMO_GRUPO = UUID.randomUUID();
    private static final UUID OTRA_PARTIDA = UUID.randomUUID();
    private static final UUID GRUPO = UUID.randomUUID();

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CustomsDeclarationGroupRepository groupRepository = mock(CustomsDeclarationGroupRepository.class);
    private final CustomsValuationService customsValuation = mock(CustomsValuationService.class);
    private final CustomsDeclarationGroupService declarationGroups = mock(CustomsDeclarationGroupService.class);
    private final CurrencyRateService currencyService = mock(CurrencyRateService.class);

    private CustomsDutyLinesService dutyLines;
    private CatalogDutyBadgeService service;

    @BeforeEach
    void montaje() {
        dutyLines = new CustomsDutyLinesService(null);
        // Sin límites: todo el carrito viaja en un bulto, salvo donde una prueba diga lo contrario.
        ReflectionTestUtils.setField(dutyLines, "maxParcelWeightGrams", 0);
        ReflectionTestUtils.setField(dutyLines, "maxParcelValueCents", 0);
        ReflectionTestUtils.setField(dutyLines, "maxParcelUnits", 0);
        // El cambio a dólares se deja uno a uno: estas pruebas hablan de la REGLA, no del tipo del día.
        lenient().when(currencyService.toUsd(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.getArgument(0, BigDecimal.class));
        service = new CatalogDutyBadgeService(productRepository, groupRepository, dutyLines, customsValuation,
                declarationGroups, currencyService, new ProductSubsidyService(currencyService));

        lenient().when(customsValuation.perArticleFeeUsdCents("ES")).thenReturn(300);
        // El derecho real lo cuenta el mismo servicio que cobra: aquí se emula su regla —3 USD por línea
        // y por bulto— para no depender de la tabla de países.
        lenient().when(customsValuation.perLineDutyUsdCents(anyString(), anyList())).thenAnswer(inv -> {
            List<CustomsDutyLinesService.DutyParcel> bultos = inv.getArgument(1);
            return bultos.stream().mapToInt(CustomsDutyLinesService.DutyParcel::tariffLines).sum() * 300;
        });
        lenient().when(currencyService.usdToDisplay(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(currencyService.formatDisplay(any(), anyString()))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).toPlainString() + " €");
        lenient().when(declarationGroups.describeFor(any(), anyString()))
                .thenAnswer(inv -> descripcionDe(inv.getArgument(0)));
    }

    @Test
    void unProductoDelMismoGrupoNoSumaArancel() {
        // Los dos viajan con la MISMA descripción —la del grupo aprobado—, así que la aduana los cuenta
        // como una sola línea: añadirlo al carrito no cuesta un derecho más.
        catalogoCon(producto(EN_CARRITO, "620443", "Women's dresses"), producto(MISMO_GRUPO, "620443", "Women's dresses"));

        Map<UUID, DutyBadge> badges = service.badgesFor(List.of(EN_CARRITO), List.of(MISMO_GRUPO), "ES");

        assertThat(badges.get(MISMO_GRUPO).extraDutyCents()).isZero();
    }

    @Test
    void unProductoDeOtraPartidaAbreLineaNueva() {
        catalogoCon(producto(EN_CARRITO, "620443", "Women's dresses"), producto(OTRA_PARTIDA, "610990", "T-shirts"));

        Map<UUID, DutyBadge> badges = service.badgesFor(List.of(EN_CARRITO), List.of(OTRA_PARTIDA), "ES");

        assertThat(badges.get(OTRA_PARTIDA).extraDutyCents()).isEqualTo(300);
        assertThat(badges.get(OTRA_PARTIDA).extraDutyFormatted()).isEqualTo("3.00 €");
    }

    @Test
    void siElPaisNoCobraPorArticuloElDistintivoDESAPARECE() {
        // Cuando el régimen de 3 EUR termine (1-jul-2028) se pondrá el importe a cero en los 27 países. El
        // distintivo NO lleva interruptor propio: se deriva del importe, para que no haya una segunda
        // fuente de verdad que alguien olvide mover.
        when(customsValuation.perArticleFeeUsdCents("ES")).thenReturn(0);

        assertThat(service.badgesFor(List.of(EN_CARRITO), List.of(MISMO_GRUPO), "ES")).isEmpty();
    }

    @Test
    void conElCarritoVacioNoHayImporteQuePrometer() {
        // Sin referencia el mensaje no significa nada. El grupo sí se devuelve: el filtro «ver los que no
        // suman arancel» funciona desde la ficha aunque no haya carrito.
        catalogoCon(producto(MISMO_GRUPO, "620443", "Women's dresses"));
        grupoAprobado();

        Map<UUID, DutyBadge> badges = service.badgesFor(List.of(), List.of(MISMO_GRUPO), "ES");

        assertThat(badges.get(MISMO_GRUPO).extraDutyCents()).isNull();
        assertThat(badges.get(MISMO_GRUPO).dutyGroupId()).isEqualTo(GRUPO);
    }

    @Test
    void unCarritoQuePartaEnDosBultosNOMarcaComoGratisElMismoGrupo() {
        // El derecho se cobra por línea Y POR BULTO: dos productos del mismo grupo en bultos distintos
        // pagan 3 EUR cada uno. Comparar grupos sin repartir en bultos daría una promesa falsa y esos
        // 3 EUR los pondría el comercio al despachar.
        ReflectionTestUtils.setField(dutyLines, "maxParcelWeightGrams", 600);
        catalogoCon(producto(EN_CARRITO, "620443", "Women's dresses"), producto(MISMO_GRUPO, "620443", "Women's dresses"));

        Map<UUID, DutyBadge> badges = service.badgesFor(List.of(EN_CARRITO), List.of(MISMO_GRUPO), "ES");

        assertThat(badges.get(MISMO_GRUPO).extraDutyCents()).isEqualTo(300);
    }

    @Test
    void soloSeOfreceElFiltroDeUnGrupoYaAPROBADO() {
        // Un grupo sin aprobar no agrupa nada: ofrecer «ver los que no suman arancel» sería una promesa
        // que la aduana no va a cumplir.
        catalogoCon(producto(MISMO_GRUPO, "620443", "Women's dresses"));
        when(groupRepository.findAllByOrderByProductCountDesc()).thenReturn(List.of(
                CustomsDeclarationGroupEntity.builder().id(GRUPO).hs6("620443").material("POLYESTER")
                        .usageCode("DRESS").ename("Women's dresses").approvedAt(null).build()));

        Map<UUID, DutyBadge> badges = service.badgesFor(List.of(), List.of(MISMO_GRUPO), "ES");

        assertThat(badges.get(MISMO_GRUPO).dutyGroupId()).isNull();
    }

    /**
     * Quién paga el derecho: el distintivo que la tarjeta enseña junto a la valoración.
     *
     * <p>Es una propiedad del PRODUCTO y no una comparación con el carrito, así que se sabe también con el
     * carrito vacío —al revés que el «sin arancel adicional» de arriba—. Se mide contra el derecho de UNA
     * línea porque el distintivo viaja en la tarjeta, donde todavía no hay pedido.
     */
    @Test
    void laBolsaQueCubreElDerechoSeAnuncia() {
        ProductEntity p = producto(MISMO_GRUPO, "620443", "Dress");
        p.setCurrency("CNY");
        p.setDutyUserCny(new BigDecimal("3.00"));
        catalogoCon(p);

        assertThat(service.badgesFor(List.of(), List.of(p.getId()), "ES").get(p.getId()).dutyCovered()).isTrue();
    }

    @Test
    void laBolsaCortaNoSeAnuncia() {
        ProductEntity p = producto(MISMO_GRUPO, "620443", "Dress");
        p.setCurrency("CNY");
        p.setDutyUserCny(new BigDecimal("1.00"));
        catalogoCon(p);

        assertThat(service.badgesFor(List.of(), List.of(p.getId()), "ES").get(p.getId()).dutyCovered()).isFalse();
    }

    @Test
    void sinBolsaNoHayPromesa() {
        ProductEntity p = producto(MISMO_GRUPO, "620443", "Dress");
        catalogoCon(p);

        assertThat(service.badgesFor(List.of(), List.of(p.getId()), "ES").get(p.getId()).dutyCovered()).isFalse();
    }

    /**
     * Los 3 EUR son del régimen de la Unión: hoy solo los cobran los 27 de la UE. Prometer «aranceles
     * pagados» a quien compra desde un país que no los cobra es anunciar una ventaja que no existe.
     */
    @Test
    void fueraDeLaUnionNoSePrometeNada() {
        ProductEntity p = producto(MISMO_GRUPO, "620443", "Dress");
        p.setCurrency("CNY");
        p.setDutyUserCny(new BigDecimal("50.00"));
        catalogoCon(p);
        when(customsValuation.perArticleFeeUsdCents("US")).thenReturn(0);

        assertThat(service.badgesFor(List.of(), List.of(p.getId()), "US")).isEmpty();
    }

    private void catalogoCon(ProductEntity... productos) {
        List<ProductEntity> todos = List.of(productos);
        lenient().when(productRepository.findAllById(anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(0);
            return todos.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        lenient().when(groupRepository.findAllByOrderByProductCountDesc()).thenReturn(List.of());
    }

    private void grupoAprobado() {
        when(groupRepository.findAllByOrderByProductCountDesc()).thenReturn(List.of(
                CustomsDeclarationGroupEntity.builder().id(GRUPO).hs6("620443").material("POLYESTER")
                        .usageCode("DRESS").ename("Women's dresses").approvedAt(Instant.now()).build()));
    }

    private static ProductEntity producto(UUID id, String hs, String descripcion) {
        ProductEntity p = new ProductEntity();
        p.setId(id);
        p.setHsCode(hs);
        p.setCountryOfOrigin("CN");
        p.setCustomsMaterial("Polyester");
        p.setCustomsUsage("Dress");
        p.setPackageWeightGrams(500);
        ProductTranslationEntity en = new ProductTranslationEntity();
        en.setLanguage("en");
        en.setTitle(descripcion);
        p.setTranslations(List.of(en));
        return p;
    }

    private static String descripcionDe(Object producto) {
        return Optional.ofNullable(CustomsDutyLinesService.declaredDescriptionOf((ProductEntity) producto))
                .orElse("");
    }
}
