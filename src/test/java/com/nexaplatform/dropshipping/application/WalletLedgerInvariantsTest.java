package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.usecase.impl.WalletUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
import com.nexaplatform.dropshipping.domain.repository.WalletTransactionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Invariantes del monedero: el saldo es dinero, y el libro tiene que cuadrar.
 *
 * <p>El monedero paga pedidos, así que un descuadre aquí es dinero real. Lo que se fija: no se cobra
 * más de lo disponible, el saldo nunca queda en negativo, la clave de idempotencia impide que un
 * reintento duplique el apunte, y cada movimiento deja registrado el saldo resultante para poder
 * reconstruir el libro.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletLedgerInvariantsTest {

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

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private WalletUseCaseImpl useCase() {
        return new WalletUseCaseImpl(walletRepository, txRepository, auditLogger, currencyService, walletIndexer,
                walletSearchService);
    }

    private Wallet wallet(long balanceCents, long holdCents) {
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setBalanceUsdCents(balanceCents);
        w.setHoldUsdCents(holdCents);
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(txRepository.save(any())).thenAnswer(i -> {
            WalletTransaction tx = i.getArgument(0);
            if (tx.getId() == null) {
                tx.setId(UUID.randomUUID());   // la auditoría lo mete en un Map.of, que no admite nulos
            }
            return tx;
        });
        return w;
    }


    /**
     * Sujeto bajo prueba, construido una sola vez por test. Se instancia en {@code @BeforeEach} y no
     * en la declaración del campo porque los dobles de prueba se inyectan DESPUÉS de crear la clase:
     * hacerlo antes lo dejaría con todas las dependencias a nulo. Tenerlo aparte permite además que la
     * lambda de cada aserción contenga una sola llamada capaz de lanzar, así que el fallo esperado sólo
     * puede venir del método bajo prueba.
     */
    private WalletUseCaseImpl subject;

    @BeforeEach
    void buildSubject() {
        subject = useCase();
        // Los movimientos de saldo ahora cargan la wallet con bloqueo de fila (findByUserIdForUpdate). En los
        // tests se delega al mismo stub que findByUserId para no duplicar cada `when(...)`.
        org.mockito.Mockito.lenient().when(walletRepository.findByUserIdForUpdate(any()))
                .thenAnswer(inv -> walletRepository.findByUserId(inv.getArgument(0)));
        // El saldo se mueve con un UPDATE atómico en la base (applyBalanceDelta), no leyendo y guardando la
        // entidad: es lo que impide el doble gasto entre checkouts simultáneos. Aquí se reproduce esa misma
        // semántica sobre la wallet simulada — aplica el delta solo si no deja el saldo en negativo — para
        // que las pruebas sigan comprobando la regla y no el mecanismo.
        org.mockito.Mockito.lenient().when(walletRepository.applyBalanceDelta(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    var actual = walletRepository.findByUserId(inv.getArgument(0));
                    if (actual.isEmpty()) {
                        return false;
                    }
                    long nuevo = actual.get().getBalanceUsdCents() + (long) inv.getArgument(1);
                    if (nuevo < 0) {
                        return false;
                    }
                    actual.get().setBalanceUsdCents(nuevo);
                    return true;
                });
        org.mockito.Mockito.lenient().when(walletRepository.currentBalanceCents(any()))
                .thenAnswer(inv -> walletRepository.findByUserId(inv.getArgument(0))
                        .map(w -> w.getBalanceUsdCents()).orElse(0L));

    }

    // ---------------------------------------------------------------- no gastar lo que no hay

    @Test
    void noSeCobraMasDeLoQueHayEnElMonedero() {
        Wallet w = wallet(5_000L, 0L);

        assertThatThrownBy(() -> subject.charge(userId, 5_001L, orderId, "k1", "Pedido"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient");

        assertThat(w.getBalanceUsdCents()).isEqualTo(5_000L);
        verify(txRepository, never()).save(any());
    }

    @Test
    void cobrarExactamenteElSaldoDisponibleSiSePuedeYDejaElMonederoACero() {
        Wallet w = wallet(5_000L, 0L);

        WalletTransaction tx = useCase().charge(userId, 5_000L, orderId, "k1", "Pedido");

        assertThat(w.getBalanceUsdCents()).isZero();
        assertThat(tx.getAmountUsdCents()).isEqualTo(-5_000L);   // el cargo se anota en negativo
        assertThat(tx.getBalanceAfterCents()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -100_000L})
    void noSeAdmiteUnCargoDeImporteCeroONegativo(long amount) {
        // Un cargo negativo sería un abono encubierto: dinero regalado sin pasar por la pasarela.
        wallet(5_000L, 0L);

        assertThatThrownBy(() -> subject.charge(userId, amount, orderId, "k1", "Pedido"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");

        verify(txRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void noSeAdmiteUnAbonoDeImporteCeroONegativo(long amount) {
        wallet(5_000L, 0L);

        assertThatThrownBy(() -> subject.deposit(userId, amount, null, "k1", "Recarga"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    // ---------------------------------------------------------------- idempotencia

    @Test
    void repetirElMismoAbonoConLaMismaClaveNoDuplicaElSaldo() {
        // Es la protección que sostiene los reintentos de webhook: el mismo cobro puede llegar dos veces.
        Wallet w = wallet(0L, 0L);
        WalletTransaction ya = WalletTransaction.builder().id(UUID.randomUUID()).amountUsdCents(2_500L).build();
        when(txRepository.findByIdempotencyKey("deposit-1")).thenReturn(Optional.of(ya));

        assertThat(useCase().deposit(userId, 2_500L, null, "deposit-1", "Recarga")).isSameAs(ya);

        assertThat(w.getBalanceUsdCents()).isZero();
        verify(txRepository, never()).save(any());
    }

    @Test
    void repetirElMismoCargoConLaMismaClaveNoCobraDosVeces() {
        Wallet w = wallet(10_000L, 0L);
        WalletTransaction ya = WalletTransaction.builder().id(UUID.randomUUID()).amountUsdCents(-5_000L).build();
        when(txRepository.findByIdempotencyKey("order-1")).thenReturn(Optional.of(ya));

        assertThat(useCase().charge(userId, 5_000L, orderId, "order-1", "Pedido")).isSameAs(ya);

        assertThat(w.getBalanceUsdCents()).isEqualTo(10_000L);
    }

    // ---------------------------------------------------------------- el libro cuadra

    @Test
    void cadaMovimientoDejaAnotadoElSaldoResultante() {
        // Sin balanceAfter no se puede reconstruir el libro ni detectar un descuadre.
        Wallet w = wallet(10_000L, 0L);

        WalletTransaction abono = useCase().deposit(userId, 2_500L, null, "d1", "Recarga");
        assertThat(abono.getBalanceAfterCents()).isEqualTo(12_500L);
        assertThat(w.getBalanceUsdCents()).isEqualTo(12_500L);

        WalletTransaction cargo = useCase().charge(userId, 2_000L, orderId, "c1", "Pedido");
        assertThat(cargo.getBalanceAfterCents()).isEqualTo(10_500L);
        assertThat(w.getBalanceUsdCents()).isEqualTo(10_500L);
    }

    @Test
    void elMovimientoDejaConstanciaDelPedidoOElPagoQueLoOrigino() {
        // La trazabilidad es lo que permite responder "¿por qué me han cobrado esto?".
        wallet(10_000L, 0L);
        UUID paymentId = UUID.randomUUID();

        assertThat(useCase().deposit(userId, 2_500L, paymentId, "d1", "Recarga").getPaymentId())
                .isEqualTo(paymentId);
        assertThat(useCase().charge(userId, 2_000L, orderId, "c1", "Pedido").getOrderId())
                .isEqualTo(orderId);
    }

    // ---------------------------------------------------------------- ajustes manuales del admin

    @Test
    void unAjusteManualNegativoNoPuedeDejarElSaldoEnNegativo() {
        Wallet w = wallet(1_000L, 0L);

        assertThatThrownBy(() -> subject.adminAdjustEntry(userId, -1_001L, "Corrección", "a1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("negative");

        assertThat(w.getBalanceUsdCents()).isEqualTo(1_000L);
    }

    @Test
    void unAjusteManualNegativoValidoSiDescuenta() {
        // DROP-603: los ajustes negativos llegaron a fallar; se comprueba que siguen funcionando.
        Wallet w = wallet(5_000L, 0L);

        WalletTransaction tx = useCase().adminAdjustEntry(userId, -1_500L, "Devolución fuera de plazo", "a1");

        assertThat(w.getBalanceUsdCents()).isEqualTo(3_500L);
        assertThat(tx.getDescription()).contains("Devolución fuera de plazo");
    }

    @Test
    void unAjusteManualExigeExplicacionYNoAdmiteImporteCero() {
        // Un movimiento de dinero hecho a mano sin motivo escrito es imposible de auditar después.
        wallet(5_000L, 0L);

        assertThatThrownBy(() -> subject.adminAdjustEntry(userId, 100L, null, "a1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subject.adminAdjustEntry(userId, 100L, "   ", "a1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subject.adminAdjustEntry(userId, 0L, "Motivo", "a1"))
                .isInstanceOf(BusinessException.class);

        verify(txRepository, never()).save(any());
    }

    @Test
    void elIngresoManualDelAdminQuedaEtiquetadoAunqueNoSeEscribaMotivo() {
        wallet(0L, 0L);

        WalletTransaction tx = useCase().adminTopup(userId, 5_000L, null, "t1");

        assertThat(tx.getDescription()).isEqualTo("Admin manual top-up");
        assertThat(tx.getBalanceAfterCents()).isEqualTo(5_000L);
    }

    @Test
    void operarSobreUnMonederoQueNoExisteFalla() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.charge(userId, 100L, orderId, "k1", "Pedido"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void abrirElMonederoDeUnUsuarioNuevoLoCreaAceroYNoSiembraSaldo() {
        // El monedero NO se siembra con saldo: si naciera con dinero, sería dinero regalado.
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenAnswer(i -> {
            Wallet w = i.getArgument(0);
            w.setId(UUID.randomUUID());
            return w;
        });

        Wallet nuevo = useCase().getOrCreate(userId);

        assertThat(nuevo.getBalanceUsdCents()).isZero();
        verify(walletRepository).save(any());
    }
}
