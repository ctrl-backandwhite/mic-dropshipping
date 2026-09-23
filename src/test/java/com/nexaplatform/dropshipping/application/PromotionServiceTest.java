package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.application.service.PromotionService.Discounted;
import com.nexaplatform.dropshipping.domain.enums.PromotionKind;
import com.nexaplatform.dropshipping.domain.enums.PromotionScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRedemptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromotionServiceTest {

    @Mock
    PromotionRepository promotionRepository;
    @Mock
    PromotionTargetRepository targetRepository;
    @Mock
    CategoryRepository categoryRepository;
    @Mock
    PromotionRedemptionRepository redemptionRepository;

    @InjectMocks
    PromotionService service;

    private static final BigDecimal PRECIO = new BigDecimal("100.00");

    private static PromotionEntity promo(String name, String percent, PromotionScope scope, PromotionKind kind) {
        return PromotionEntity.builder().id(UUID.randomUUID()).name(name).percentOff(new BigDecimal(percent))
                .scope(scope).kind(kind).active(true).createdAt(Instant.now()).build();
    }

    private static ProductEntity producto(UUID categoryId) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        if (categoryId != null) {
            CategoryEntity c = new CategoryEntity();
            c.setId(categoryId);
            p.setCategory(c);
        }
        return p;
    }

    private void live(PromotionEntity... promos) {
        when(promotionRepository.findLive(any())).thenReturn(List.of(promos));
    }

    // ─────────────────────── descuento básico ───────────────────────

    @Test
    void unaRebajaGlobalDescuentaElPorcentajeYLoAnuncia() {
        live(promo("Rebajas de invierno", "30", PromotionScope.ALL, PromotionKind.SEASONAL));

        Discounted d = service.applyAutomatic(producto(null), PRECIO, null);

        assertThat(d.applies()).isTrue();
        assertThat(d.finalAmount()).isEqualByComparingTo("70.00");
        assertThat(d.percentOff()).isEqualByComparingTo("30");
        assertThat(d.promotionName()).isEqualTo("Rebajas de invierno");
    }

    @Test
    void sinPromocionesElPrecioNoSeToca() {
        live();

        Discounted d = service.applyAutomatic(producto(null), PRECIO, null);

        assertThat(d.applies()).isFalse();
        assertThat(d.finalAmount()).isEqualByComparingTo(PRECIO);
    }

    @Test
    void unDescuentoDeImporteFijoRestaEseImporte() {
        PromotionEntity p = PromotionEntity.builder().id(UUID.randomUUID()).name("5 € menos").amountOffCents(500)
                .scope(PromotionScope.ALL).kind(PromotionKind.FLASH).active(true).createdAt(Instant.now()).build();
        live(p);

        assertThat(service.applyAutomatic(producto(null), PRECIO, null).finalAmount()).isEqualByComparingTo("95.00");
    }

    // ─────────────────────── regla 1: gana la mayor, no se acumulan ───────────────────────

    @Test
    void conDosRebajasAplicablesGanaLaQueMasDescuenta() {
        live(promo("Invierno", "20", PromotionScope.ALL, PromotionKind.SEASONAL),
                promo("Liquidación", "45", PromotionScope.ALL, PromotionKind.CLEARANCE));

        Discounted d = service.applyAutomatic(producto(null), PRECIO, null);

        // 45%, NO 20+45 ni encadenado: se aplica una sola.
        assertThat(d.finalAmount()).isEqualByComparingTo("55.00");
        assertThat(d.promotionName()).isEqualTo("Liquidación");
    }

    @Test
    void elCuponCompiteConLaRebajaYGanaElMejorParaElCliente() {
        live(promo("Invierno", "40", PromotionScope.ALL, PromotionKind.SEASONAL));
        PromotionEntity cupon = promo("CUPON10", "10", PromotionScope.ALL, PromotionKind.COUPON);

        Discounted d = service.applyWithCoupon(producto(null), PRECIO, null, cupon);

        // El cupón es PEOR que la rebaja: el cliente se queda con la rebaja, no pierde por canjearlo.
        assertThat(d.finalAmount()).isEqualByComparingTo("60.00");
        assertThat(d.promotionName()).isEqualTo("Invierno");
    }

    @Test
    void unCuponMejorQueLaRebajaSustituyeALaRebaja() {
        live(promo("Invierno", "10", PromotionScope.ALL, PromotionKind.SEASONAL));
        PromotionEntity cupon = promo("VIP50", "50", PromotionScope.ALL, PromotionKind.COUPON);

        Discounted d = service.applyWithCoupon(producto(null), PRECIO, null, cupon);

        assertThat(d.finalAmount()).isEqualByComparingTo("50.00");
        assertThat(d.promotionName()).isEqualTo("VIP50");
    }

    // ─────────────────────── regla 2: nunca por debajo de coste ───────────────────────

    @Test
    void elSueloDeCosteRecortaLaRebajaQueVenderiaAPerdida() {
        live(promo("Liquidación total", "80", PromotionScope.ALL, PromotionKind.CLEARANCE));

        // Coste + envío = 60. Un 80% dejaría el precio en 20: por debajo, cada venta pierde dinero.
        Discounted d = service.applyAutomatic(producto(null), PRECIO, new BigDecimal("60.00"));

        assertThat(d.finalAmount()).isEqualByComparingTo("60.00");
        // Y se anuncia el descuento REAL (40%), no el nominal del 80%.
        assertThat(d.percentOff()).isEqualByComparingTo("40");
    }

    @Test
    void siElSueloSeComeLaRebajaEnteraNoSeAnunciaNinguna() {
        live(promo("Imposible", "30", PromotionScope.ALL, PromotionKind.SEASONAL));

        // El suelo ya está por encima del precio: no hay rebaja posible.
        Discounted d = service.applyAutomatic(producto(null), PRECIO, new BigDecimal("120.00"));

        assertThat(d.applies()).isFalse();
        assertThat(d.finalAmount()).isEqualByComparingTo(PRECIO);
    }

    @Test
    void elSueloSeAplicaDespuesDeElegirLaMejorPromocion() {
        // Si el suelo se aplicara ANTES, la del 80% quedaría recortada a 60 y perdería frente a la del
        // 30% (que da 70). Elegir primero y recortar después conserva la más ventajosa.
        live(promo("Suave", "30", PromotionScope.ALL, PromotionKind.SEASONAL),
                promo("Agresiva", "80", PromotionScope.ALL, PromotionKind.CLEARANCE));

        Discounted d = service.applyAutomatic(producto(null), PRECIO, new BigDecimal("60.00"));

        assertThat(d.promotionName()).isEqualTo("Agresiva");
        assertThat(d.finalAmount()).isEqualByComparingTo("60.00");
    }

    // ─────────────────────── regla 3: alcance ───────────────────────

    @Test
    void unaRebajaPorProductoSoloAlcanzaAEseProducto() {
        ProductEntity dentro = producto(null);
        ProductEntity fuera = producto(null);
        PromotionEntity p = promo("Solo este", "50", PromotionScope.PRODUCT, PromotionKind.FLASH);
        live(p);
        when(targetRepository.findByPromotionId(p.getId())).thenReturn(
                List.of(PromotionTargetEntity.builder().promotionId(p.getId()).productId(dentro.getId()).build()));

        assertThat(service.applyAutomatic(dentro, PRECIO, null).applies()).isTrue();
        assertThat(service.applyAutomatic(fuera, PRECIO, null).applies()).isFalse();
    }

    @Test
    void unaRebajaPorCategoriaAlcanzaTambienALasSubcategorias() {
        // Rebajar «Ropa de mujer» tiene que alcanzar a «Vestidos», que cuelga de ella: si no, habría
        // que listar cada subcategoría a mano y las nuevas se quedarían fuera sin que nadie lo note.
        UUID madre = UUID.randomUUID();
        UUID hija = UUID.randomUUID();
        CategoryEntity padre = new CategoryEntity();
        padre.setId(madre);
        CategoryEntity sub = new CategoryEntity();
        sub.setId(hija);
        sub.setParent(padre);
        when(categoryRepository.findById(hija)).thenReturn(Optional.of(sub));
        when(categoryRepository.findById(madre)).thenReturn(Optional.of(padre));

        PromotionEntity p = promo("Ropa de mujer -25%", "25", PromotionScope.CATEGORY, PromotionKind.SEASONAL);
        live(p);
        when(targetRepository.findByPromotionId(p.getId()))
                .thenReturn(List.of(PromotionTargetEntity.builder().promotionId(p.getId()).categoryId(madre).build()));

        assertThat(service.applyAutomatic(producto(hija), PRECIO, null).applies()).isTrue();
    }

    @Test
    void unaRebajaPorCategoriaNoAlcanzaAOtraRama() {
        UUID objetivo = UUID.randomUUID();
        UUID ajena = UUID.randomUUID();
        CategoryEntity otra = new CategoryEntity();
        otra.setId(ajena);
        when(categoryRepository.findById(ajena)).thenReturn(Optional.of(otra));

        PromotionEntity p = promo("Calzado", "30", PromotionScope.CATEGORY, PromotionKind.SEASONAL);
        live(p);
        when(targetRepository.findByPromotionId(p.getId())).thenReturn(
                List.of(PromotionTargetEntity.builder().promotionId(p.getId()).categoryId(objetivo).build()));

        assertThat(service.applyAutomatic(producto(ajena), PRECIO, null).applies()).isFalse();
    }

    // ─────────────────────── vigencia ───────────────────────

    @Test
    void unaPromocionCaducadaOFuturaNoEstaViva() {
        Instant ahora = Instant.now();
        PromotionEntity caducada = promo("Verano pasado", "30", PromotionScope.ALL, PromotionKind.SEASONAL);
        caducada.setEndsAt(ahora.minus(1, ChronoUnit.DAYS));
        PromotionEntity futura = promo("Black Friday", "40", PromotionScope.ALL, PromotionKind.SEASONAL);
        futura.setStartsAt(ahora.plus(30, ChronoUnit.DAYS));

        assertThat(caducada.isLiveAt(ahora)).isFalse();
        assertThat(futura.isLiveAt(ahora)).isFalse();
    }

    @Test
    void unaPromocionDesactivadaNoSeAplicaAunqueEsteEnFecha() {
        PromotionEntity p = promo("Pausada", "30", PromotionScope.ALL, PromotionKind.SEASONAL);
        p.setActive(false);

        assertThat(p.isLiveAt(Instant.now())).isFalse();
    }

    @Test
    void unCuponAgotadoDejaDeEstarVivo() {
        PromotionEntity p = promo("SOLO100", "20", PromotionScope.ALL, PromotionKind.COUPON);
        p.setMaxUses(100);
        p.setUsedCount(100);

        assertThat(p.isLiveAt(Instant.now())).isFalse();
    }

    @Test
    void unaVigenciaAbiertaPorLosDosLadosEstaViva() {
        assertThat(promo("Permanente", "10", PromotionScope.ALL, PromotionKind.SEASONAL).isLiveAt(Instant.now()))
                .isTrue();
    }

    // ─────────────────────── cupones ───────────────────────

    @Test
    void elCuponSeResuelvePorCodigoSinDistinguirMayusculas() {
        PromotionEntity p = promo("VERANO25", "25", PromotionScope.ALL, PromotionKind.COUPON);
        p.setCode("VERANO25");
        when(promotionRepository.findByCodeIgnoreCase("verano25")).thenReturn(Optional.of(p));

        assertThat(service.findLiveCoupon("verano25")).isPresent();
    }

    @Test
    void unCodigoVacioNoResuelveNingunCupon() {
        assertThat(service.findLiveCoupon("  ")).isEmpty();
        assertThat(service.findLiveCoupon(null)).isEmpty();
    }

    @Test
    void loscuponesNoRebajanElEscaparate() {
        // Un cupón visible en el catálogo rebajaría el precio de toda la tienda sin que nadie lo canjee.
        live(promo("CUPON30", "30", PromotionScope.ALL, PromotionKind.COUPON));

        assertThat(service.applyAutomatic(producto(null), PRECIO, null).applies()).isFalse();
    }

    @Test
    void soloLasPromocionesAutomaticasSeAnuncianSolas() {
        assertThat(PromotionKind.SEASONAL.isAutomatic()).isTrue();
        assertThat(PromotionKind.FLASH.isAutomatic()).isTrue();
        assertThat(PromotionKind.CLEARANCE.isAutomatic()).isTrue();
        assertThat(PromotionKind.COUPON.isAutomatic()).isFalse();
        assertThat(PromotionKind.REFERRAL.isAutomatic()).isFalse();
    }

    // ─────────────────────── condiciones de canje del cupón ───────────────────────

    private PromotionEntity cupon(String code) {
        PromotionEntity p = promo(code, "20", PromotionScope.ALL, PromotionKind.COUPON);
        p.setCode(code);
        when(promotionRepository.findByCodeIgnoreCase(code)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void unCodigoInexistenteLoDiceEnVezDeUnErrorGenerico() {
        when(promotionRepository.findByCodeIgnoreCase("NADA")).thenReturn(Optional.empty());

        PromotionService.CouponCheck c = service.checkCoupon("NADA", UUID.randomUUID(), 10_00);

        assertThat(c.valid()).isFalse();
        assertThat(c.reason()).isEqualTo("Ese código no existe");
    }

    @Test
    void unCuponCaducadoDiceQueCaduco() {
        PromotionEntity p = cupon("VIEJO");
        p.setEndsAt(Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(service.checkCoupon("VIEJO", UUID.randomUUID(), 10_00).reason()).isEqualTo("Ese cupón ha caducado");
    }

    @Test
    void unCuponFuturoDiceQueAunNoEmpieza() {
        PromotionEntity p = cupon("PRONTO");
        p.setStartsAt(Instant.now().plus(2, ChronoUnit.DAYS));

        assertThat(service.checkCoupon("PRONTO", UUID.randomUUID(), 10_00).reason())
                .isEqualTo("Ese cupón todavía no ha empezado");
    }

    @Test
    void unCuponAgotadoGlobalmenteNoSeCanjea() {
        PromotionEntity p = cupon("AGOTADO");
        p.setMaxUses(50);
        p.setUsedCount(50);

        assertThat(service.checkCoupon("AGOTADO", UUID.randomUUID(), 10_00).reason())
                .isEqualTo("Ese cupón se ha agotado");
    }

    @Test
    void unCuponNominativoSoloSirveASuDuenno() {
        UUID duenno = UUID.randomUUID();
        PromotionEntity p = cupon("SOLOTUYO");
        p.setUserId(duenno);

        assertThat(service.checkCoupon("SOLOTUYO", UUID.randomUUID(), 10_00).reason())
                .isEqualTo("Ese cupón no está disponible para tu cuenta");
        assertThat(service.checkCoupon("SOLOTUYO", duenno, 10_00).valid()).isTrue();
    }

    @Test
    void elTopePorPersonaImpideQueUnoSoloAgoteElCupon() {
        // Sin este tope, un cupón de 100 usos se lo lleva entero quien lo publique en un foro.
        UUID quien = UUID.randomUUID();
        PromotionEntity p = cupon("UNAVEZ");
        p.setMaxUsesPerUser(1);
        when(redemptionRepository.countByPromotionIdAndUserId(p.getId(), quien)).thenReturn(1L);

        assertThat(service.checkCoupon("UNAVEZ", quien, 10_00).reason()).isEqualTo("Ya has usado ese cupón");
    }

    @Test
    void elPedidoMinimoSeComunicaConSuImporte() {
        PromotionEntity p = cupon("MIN50");
        p.setMinOrderCents(50_00);

        assertThat(service.checkCoupon("MIN50", UUID.randomUUID(), 10_00).reason())
                .isEqualTo("Ese cupón necesita un pedido mínimo de 50.00");
    }

    @Test
    void unCuponPausadoNoSeCanjeaAunqueEsteEnFecha() {
        PromotionEntity p = cupon("PAUSADO");
        p.setActive(false);

        assertThat(service.checkCoupon("PAUSADO", UUID.randomUUID(), 10_00).reason())
                .isEqualTo("Ese cupón ya no está disponible");
    }

    @Test
    void unCuponValidoPasaLasCuatroCondiciones() {
        cupon("BUENO");

        assertThat(service.checkCoupon("BUENO", UUID.randomUUID(), 100_00).valid()).isTrue();
    }

    @Test
    void unReintentoDePagoNoCanjeaDosVecesElMismoCupon() {
        UUID promo = UUID.randomUUID();
        UUID pedido = UUID.randomUUID();
        PromotionEntity p = promo("X", "10", PromotionScope.ALL, PromotionKind.COUPON);
        when(promotionRepository.findById(promo)).thenReturn(Optional.of(p));
        when(redemptionRepository.existsByPromotionIdAndOrderId(promo, pedido)).thenReturn(true);

        service.recordUse(promo, UUID.randomUUID(), pedido, 500);

        verify(promotionRepository, never()).save(any());
    }

    /* ============================ reachFilter (botón «Ver los productos») ============================ */

    /** Una promoción global no filtra nada: el botón lleva al catálogo entero (vacío = sin filtro). */
    @Test
    void reachFilterDeUnaPromocionGlobalNoFiltra() {
        PromotionEntity p = promo("Invierno", "30", PromotionScope.ALL, PromotionKind.SEASONAL);
        when(promotionRepository.findById(p.getId())).thenReturn(java.util.Optional.of(p));

        assertThat(service.reachFilter(p.getId())).isEmpty();
    }

    /** Alcance por productos: solo pasan los IDs objetivo. */
    @Test
    void reachFilterPorProductoSoloDejaPasarLosObjetivo() {
        PromotionEntity p = promo("Selección", "20", PromotionScope.PRODUCT, PromotionKind.FLASH);
        ProductEntity dentro = producto(null);
        ProductEntity fuera = producto(null);
        when(promotionRepository.findById(p.getId())).thenReturn(java.util.Optional.of(p));
        when(targetRepository.findByPromotionId(p.getId()))
                .thenReturn(List.of(PromotionTargetEntity.builder().productId(dentro.getId()).build()));

        java.util.function.Predicate<ProductEntity> f = service.reachFilter(p.getId()).orElseThrow();
        assertThat(f.test(dentro)).isTrue();
        assertThat(f.test(fuera)).isFalse();
    }

    /** Alcance por categoría: cuenta la jerarquía (un producto de una subcategoría de la objetivo entra). */
    @Test
    void reachFilterPorCategoriaCuentaLaJerarquia() {
        UUID padre = UUID.randomUUID();
        UUID hijo = UUID.randomUUID();
        CategoryEntity catPadre = new CategoryEntity();
        catPadre.setId(padre);
        CategoryEntity catHijo = new CategoryEntity();
        catHijo.setId(hijo);
        catHijo.setParent(catPadre);
        when(categoryRepository.findById(hijo)).thenReturn(java.util.Optional.of(catHijo));
        when(categoryRepository.findById(padre)).thenReturn(java.util.Optional.of(catPadre));

        PromotionEntity p = promo("Ropa", "25", PromotionScope.CATEGORY, PromotionKind.SEASONAL);
        when(promotionRepository.findById(p.getId())).thenReturn(java.util.Optional.of(p));
        when(targetRepository.findByPromotionId(p.getId()))
                .thenReturn(List.of(PromotionTargetEntity.builder().categoryId(padre).build()));

        java.util.function.Predicate<ProductEntity> f = service.reachFilter(p.getId()).orElseThrow();
        assertThat(f.test(producto(hijo))).isTrue(); // subcategoría de la objetivo
        assertThat(f.test(producto(UUID.randomUUID()))).isFalse(); // categoría ajena
    }

    /** Una promoción que no está viva (o no existe) no filtra: cae al catálogo completo. */
    @Test
    void reachFilterDeUnaPromocionApagadaNoFiltra() {
        PromotionEntity p = promo("Apagada", "40", PromotionScope.PRODUCT, PromotionKind.FLASH);
        p.setActive(false);
        when(promotionRepository.findById(p.getId())).thenReturn(java.util.Optional.of(p));

        assertThat(service.reachFilter(p.getId())).isEmpty();
        assertThat(service.reachFilter(null)).isEmpty();
    }
}
