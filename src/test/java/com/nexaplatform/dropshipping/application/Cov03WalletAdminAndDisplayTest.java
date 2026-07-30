package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase.WalletPage;
import com.nexaplatform.dropshipping.application.usecase.impl.WalletUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
import com.nexaplatform.dropshipping.domain.repository.WalletTransactionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Monedero: lo que ve el usuario (saldo disponible, importes ya formateados) y lo que ve el
 * administrador (listado, filtros y paginación). El libro mayor tiene sus propias pruebas; aquí se fija
 * la presentación del dinero y las retenciones, que es donde un fallo se traduce en «me dice que tengo
 * saldo y luego no me deja pagar».
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov03WalletAdminAndDisplayTest {

    @Mock
    WalletRepository walletRepository;
    @Mock
    WalletTransactionRepository txRepository;
    @Mock
    AuditLogger auditLogger;
    @Mock
    CurrencyRateService currencyService;
    @Mock
    WalletIndexer walletIndexer;
    @Mock
    WalletSearchService walletSearchService;

    @InjectMocks
    WalletUseCaseImpl useCase;

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void limpiaDivisa() {
        CurrencyHolder.clear();
    }

    private Wallet wallet(long saldo, long retenido) {
        Wallet w = Wallet.builder().userId(userId).balanceUsdCents(saldo).holdUsdCents(retenido)
                .currencyDefault("USD").status("ACTIVE").build();
        w.setId(UUID.randomUUID());
        return w;
    }

    /** Simula el identificador que asigna la base de datos al guardar el apunte (la auditoría lo exige). */
    private static WalletTransaction conId(WalletTransaction tx) {
        tx.setId(UUID.randomUUID());
        return tx;
    }

    private void divisaIdentidad() {
        when(currencyService.usdTo(any(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");
        when(currencyService.localeOf(anyString())).thenReturn("es-ES");
        when(currencyService.formatIn(any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + " " + inv.getArgument(1));
    }

    /* ==================== saldo mostrado al usuario ==================== */

    @Test
    void elSaldoDisponibleDescuentaLoRetenido() {
        // Disponible es lo que se puede gastar: si mostrara el saldo bruto, el usuario intentaría pagar
        // un pedido que el monedero va a rechazar.
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet(10_000L, 2_500L)));
        divisaIdentidad();

        Wallet w = useCase.getMyWallet(userId);

        assertThat(w.getAvailableUsdCents()).isEqualTo(7_500L);
    }

    @Test
    void unaRetencionMayorQueElSaldoNoDejaElDisponibleEnNegativo() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet(1_000L, 5_000L)));
        divisaIdentidad();

        assertThat(useCase.getMyWallet(userId).getAvailableUsdCents()).isZero();
    }

    @Test
    void elSaldoSeConvierteYSeFormateaEnElBackendConLaDivisaActiva() {
        // El front solo pinta: si el importe llegara sin formatear, cada navegador lo mostraría con sus
        // propios separadores y dejaría de coincidir con lo que cobra la pasarela.
        CurrencyHolder.set("eur");
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet(12_345L, 0L)));
        when(currencyService.usdTo(any(), eq("EUR"))).thenReturn(new BigDecimal("113.50"));
        when(currencyService.symbolOf("EUR")).thenReturn("€");
        when(currencyService.localeOf("EUR")).thenReturn("es-ES");
        when(currencyService.formatIn(any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + " " + inv.getArgument(1));

        Wallet w = useCase.getMyWallet(userId);

        assertThat(w.getDisplayCurrency()).isEqualTo("EUR");
        assertThat(w.getDisplaySymbol()).isEqualTo("€");
        assertThat(w.getBalanceDisplay()).isEqualByComparingTo("113.50");
        assertThat(w.getBalanceFormatted()).isEqualTo("113.50 EUR");
        // El canónico se sigue publicando en USD: es la moneda del libro mayor.
        assertThat(w.getBalanceUsdFormatted()).isEqualTo("123.4500 USD");
    }

    @Test
    void abrirElMonederoPorPrimeraVezLoCreaYLoDaDeAltaEnElBuscador() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Wallet w = useCase.getOrCreate(userId);

        assertThat(w.getBalanceUsdCents()).isZero();
        assertThat(w.getStatus()).isEqualTo("ACTIVE");
        assertThat(w.getCurrencyDefault()).isEqualTo("USD");
        verify(walletIndexer).indexWallet(w);
    }

    /* ==================== movimientos del usuario ==================== */

    @Test
    void cadaMovimientoSePresentaConSuSignoYElSaldoResultante() {
        // Un cargo sin el signo delante se lee como un ingreso: el usuario no entendería su extracto.
        Wallet w = wallet(5_000L, 0L);
        WalletTransaction ingreso = WalletTransaction.builder().amountUsdCents(2_500L).balanceAfterCents(7_500L)
                .kind("DEPOSIT").build();
        WalletTransaction cargo = WalletTransaction.builder().amountUsdCents(-1_000L).balanceAfterCents(6_500L)
                .kind("PAYMENT").build();
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        when(txRepository.findByWalletIdOrderByCreatedAtDesc(eq(w.getId()), anyInt(), anyInt()))
                .thenReturn(List.of(ingreso, cargo));
        when(currencyService.localeOf(anyString())).thenReturn("es-ES");
        when(currencyService.formatIn(any(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0) + "$");

        List<WalletTransaction> txs = useCase.getMyTransactions(userId, 0, 20);

        assertThat(txs.get(0).getAmountFormatted()).isEqualTo("+25.00$");
        assertThat(txs.get(1).getAmountFormatted()).isEqualTo("-10.00$"); // valor absoluto con signo delante
        assertThat(txs.get(1).getBalanceAfterFormatted()).isEqualTo("65.00$");
    }

    @Test
    void elExtractoNuncaPideMasDeCienMovimientosPorPagina() {
        Wallet w = wallet(0L, 0L);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        when(txRepository.findByWalletIdOrderByCreatedAtDesc(eq(w.getId()), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(currencyService.localeOf(anyString())).thenReturn("es-ES");

        useCase.getMyTransactions(userId, 0, 100_000);

        verify(txRepository).findByWalletIdOrderByCreatedAtDesc(w.getId(), 0, 100);
    }

    @Test
    void contarMisMovimientosAbreElMonederoSiTodaviaNoExiste() {
        // Un usuario recién registrado abre "Mi monedero" antes de tener ninguna operación: si contar
        // exigiera monedero previo, la pantalla reventaría en la primera visita.
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txRepository.countByWalletId(any())).thenReturn(0L);

        assertThat(useCase.countMyTransactions(userId)).isZero();
    }

    /* ==================== retenciones ==================== */

    @Test
    void retenerReservaImporteSinTocarElSaldoContable() {
        // El saldo no se mueve hasta el cobro: la retención solo bloquea disponible.
        Wallet w = wallet(10_000L, 0L);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txRepository.save(any())).thenAnswer(inv -> conId(inv.getArgument(0)));

        WalletTransaction tx = useCase.hold(userId, 3_000L, UUID.randomUUID(), "hold-1");

        assertThat(w.getHoldUsdCents()).isEqualTo(3_000L);
        assertThat(tx.getBalanceAfterCents()).isEqualTo(10_000L);
        assertThat(tx.getKind()).isEqualTo("HOLD");
    }

    @Test
    void noSePuedeRetenerMasDeLoDisponible() {
        Wallet w = wallet(10_000L, 9_000L);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.hold(userId, 2_000L, orderId, "hold-2"))
                .isInstanceOf(BusinessException.class);
        verify(txRepository, never()).save(any());
    }

    @Test
    void liberarMasDeLoRetenidoDejaLaRetencionACeroYNoEnNegativo() {
        // Una retención negativa daría saldo disponible fantasma (más de lo que hay).
        Wallet w = wallet(10_000L, 1_000L);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txRepository.save(any())).thenAnswer(inv -> conId(inv.getArgument(0)));

        useCase.release(userId, 5_000L, UUID.randomUUID(), "rel-1");

        assertThat(w.getHoldUsdCents()).isZero();
        assertThat(w.getBalanceUsdCents()).isEqualTo(10_000L);
    }

    @Test
    void operarSobreUnMonederoInexistenteFallaComoNoEncontrado() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.hold(userId, 100L, orderId, "k")).isInstanceOf(NotFoundException.class);
    }

    /* ==================== devoluciones ==================== */

    @Test
    void laDevolucionAbonaSaldoYQuedaLigadaAlPedido() {
        Wallet w = wallet(1_000L, 0L);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txRepository.save(any())).thenAnswer(inv -> conId(inv.getArgument(0)));
        UUID orderId = UUID.randomUUID();

        WalletTransaction tx = useCase.refund(userId, 400L, orderId, "ref-1", "Pedido cancelado");

        assertThat(tx.getKind()).isEqualTo("REFUND");
        assertThat(tx.getOrderId()).isEqualTo(orderId);
        assertThat(tx.getBalanceAfterCents()).isEqualTo(1_400L);
    }

    /* ==================== listado de administración ==================== */

    private Wallet conDatos(String email, String nombre, String estado, String divisa, long saldo) {
        Wallet w = Wallet.builder().userId(UUID.randomUUID()).balanceUsdCents(saldo).holdUsdCents(0L)
                .currencyDefault(divisa).status(estado).userEmail(email).userName(nombre).build();
        w.setId(UUID.randomUUID());
        return w;
    }

    @Test
    void elListadoDeAdministracionOrdenaPorSaldoDeMayorAMenor() {
        Wallet pobre = conDatos("a@x.com", "Ana", "ACTIVE", "USD", 100L);
        Wallet rico = conDatos("b@x.com", "Bea", "ACTIVE", "USD", 900L);
        when(walletRepository.findAll()).thenReturn(List.of(pobre, rico));

        assertThat(useCase.adminListWallets(null, null, null)).containsExactly(rico, pobre);
    }

    @Test
    void losFiltrosDeEstadoYDivisaSonIndependientesDeMayusculas() {
        Wallet activa = conDatos("a@x.com", "Ana", "ACTIVE", "EUR", 100L);
        Wallet bloqueada = conDatos("b@x.com", "Bea", "BLOCKED", "USD", 900L);
        when(walletRepository.findAll()).thenReturn(List.of(activa, bloqueada));

        assertThat(useCase.adminListWallets(null, "active", null)).containsExactly(activa);
        assertThat(useCase.adminListWallets(null, null, "eur")).containsExactly(activa);
        assertThat(useCase.adminListWallets(null, "  ", "  ")).hasSize(2); // filtro en blanco = sin filtro
    }

    @Test
    void laBusquedaLibreMiraCorreoYNombreDelTitular() {
        Wallet ana = conDatos("ana@example.com", "Ana Pérez", "ACTIVE", "USD", 100L);
        Wallet bea = conDatos("bea@example.com", "Bea López", "ACTIVE", "USD", 900L);
        when(walletRepository.findAll()).thenReturn(List.of(ana, bea));

        assertThat(useCase.adminListWallets("ANA@", null, null)).containsExactly(ana);
        assertThat(useCase.adminListWallets("lópez", null, null)).containsExactly(bea);
        assertThat(useCase.adminListWallets("nadie", null, null)).isEmpty();
    }

    @Test
    void elSaldoDeLaPaginaSeLeeSiempreDeLaBaseDeDatosAunqueOrdeneElBuscador() {
        // El índice solo pagina, ordena y filtra: el dinero exige consistencia estricta, así que el saldo
        // que se muestra nunca puede venir del índice.
        Wallet a = conDatos("a@x.com", "Ana", "ACTIVE", "USD", 100L);
        Wallet b = conDatos("b@x.com", "Bea", "ACTIVE", "USD", 900L);
        when(walletSearchService.pageIds(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new WalletSearchService.IdPage(List.of(b.getId(), a.getId()), 2L)));
        when(walletRepository.findAll()).thenReturn(List.of(a, b));

        WalletPage page = useCase.pageAdminWallets(null, null, null, 0, 10);

        assertThat(page.items()).containsExactly(b, a); // el orden lo pone el índice
        assertThat(page.total()).isEqualTo(2L);
    }

    @Test
    void unIdentificadorDelIndiceQueYaNoExisteNoDejaHuecosNulosEnLaPagina() {
        // El índice puede ir por detrás de la base de datos; un monedero borrado no debe colarse como
        // fila vacía en el panel.
        Wallet a = conDatos("a@x.com", "Ana", "ACTIVE", "USD", 100L);
        when(walletSearchService.pageIds(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new WalletSearchService.IdPage(List.of(UUID.randomUUID(), a.getId()), 2L)));
        when(walletRepository.findAll()).thenReturn(List.of(a));

        assertThat(useCase.pageAdminWallets(null, null, null, 0, 10).items()).containsExactly(a);
    }

    @Test
    void sinBuscadorDisponibleLaPaginacionSeHaceEnMemoriaSinSalirseDeLaLista() {
        Wallet a = conDatos("a@x.com", "Ana", "ACTIVE", "USD", 100L);
        Wallet b = conDatos("b@x.com", "Bea", "ACTIVE", "USD", 900L);
        when(walletSearchService.pageIds(any(), any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(walletRepository.findAll()).thenReturn(List.of(a, b));

        assertThat(useCase.pageAdminWallets(null, null, null, 0, 1).items()).containsExactly(b);
        assertThat(useCase.pageAdminWallets(null, null, null, 1, 1).items()).containsExactly(a);
        // Una página más allá del final devuelve vacío en lugar de reventar.
        assertThat(useCase.pageAdminWallets(null, null, null, 9, 10).items()).isEmpty();
        assertThat(useCase.pageAdminWallets(null, null, null, 9, 10).total()).isEqualTo(2L);
    }

    @Test
    void elDetalleDeAdministracionTambienTraeElDisponible() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet(8_000L, 3_000L)));

        assertThat(useCase.adminGetWalletDetail(userId).getAvailableUsdCents()).isEqualTo(5_000L);
    }

    @Test
    void losMovimientosVistosPorElAdminTambienSeTopanACienPorPagina() {
        UUID walletId = UUID.randomUUID();
        when(txRepository.findByWalletIdOrderByCreatedAtDesc(eq(walletId), anyInt(), anyInt())).thenReturn(List.of());

        useCase.adminTransactions(walletId, 3, 500);

        verify(txRepository).findByWalletIdOrderByCreatedAtDesc(walletId, 3, 100);
    }

    @Test
    void elIngresoManualSinClaveDeIdempotenciaGeneraUnaParaNoDuplicarse() {
        // Sin clave, dos clics del administrador abonarían dos veces.
        Wallet w = wallet(0L, 0L);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txRepository.save(any())).thenAnswer(inv -> conId(inv.getArgument(0)));
        when(txRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        WalletTransaction tx = useCase.adminTopup(userId, 500L, "  ", null);

        assertThat(tx.getIdempotencyKey()).isNotBlank();
        assertThat(tx.getDescription()).isEqualTo("Admin manual top-up");
        ArgumentCaptor<String> clave = ArgumentCaptor.forClass(String.class);
        verify(txRepository).findByIdempotencyKey(clave.capture());
        assertThat(UUID.fromString(clave.getValue())).isNotNull();
    }
}
