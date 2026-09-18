package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
import com.nexaplatform.dropshipping.domain.repository.WalletTransactionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Core money-handling use case. All transactions are persisted in a single ledger
 * ({@code wallet_transaction}) for a full audit trail. Idempotency-Key prevents
 * double credit when the same recharge or refund webhook is delivered twice.
 *
 * <p>Operates on the {@link Wallet}/{@link WalletTransaction} domain models and
 * delegates persistence to the domain ports. Logic moved verbatim out of the
 * legacy {@code WalletService}, preserving the money semantics exactly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletUseCaseImpl implements WalletUseCase {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository txRepository;
    private final AuditLogger auditLogger;
    private final CurrencyRateService currencyService;
    private final WalletIndexer walletIndexer;
    private final WalletSearchService walletSearchService;

    /* ============ Read ============ */

    @Override
    @Transactional
    public Wallet getOrCreate(UUID userId) {
        return walletOf(userId);
    }

    /**
     * Monedero del usuario, creándolo si es su primera vez. El cuerpo vive aquí, sin anotación, porque el
     * resto de métodos de la clase lo necesitan dentro de SU transacción: llamando al método público desde
     * dentro de la propia clase el proxy de Spring no interviene y su {@code @Transactional} no se aplicaría.
     */
    private Wallet walletOf(UUID userId) {
        return walletRepository.findByUserId(userId).orElseGet(() -> createFor(userId));
    }

    private Wallet createFor(UUID userId) {
        Wallet w = Wallet.builder().userId(userId).balanceUsdCents(0L).holdUsdCents(0L).currencyDefault("USD")
                .status("ACTIVE").build();
        Wallet saved = walletRepository.save(w);
        walletIndexer.indexWallet(saved); // auto-sync del índice al crear la wallet
        return saved;
    }

    @Override
    @Transactional
    public Wallet getMyWallet(UUID userId) {
        Wallet w = walletOf(userId);
        long available = Math.max(0L, w.getBalanceUsdCents() - w.getHoldUsdCents());
        String currency = CurrencyHolder.get();
        BigDecimal usd = BigDecimal.valueOf(w.getBalanceUsdCents()).divide(BigDecimal.valueOf(100), 4,
                RoundingMode.HALF_UP);
        BigDecimal display = currencyService.usdTo(usd, currency);
        w.setAvailableUsdCents(available);
        w.setBalanceDisplay(display);
        w.setDisplayCurrency(currency);
        w.setDisplaySymbol(currencyService.symbolOf(currency));
        // Formateo EN EL BACKEND con la convención del país del visor (locale de la divisa activa): tanto el
        // saldo en divisa como el canónico en USD llevan los mismos separadores (coma decimal/punto de miles
        // en español), igual que Stripe. El front solo pinta estos strings.
        String vLocale = currencyService.localeOf(currency);
        BigDecimal holdUsd = BigDecimal.valueOf(w.getHoldUsdCents()).divide(BigDecimal.valueOf(100), 4,
                RoundingMode.HALF_UP);
        w.setBalanceFormatted(currencyService.formatIn(display, currency, vLocale));
        w.setBalanceUsdFormatted(currencyService.formatIn(usd, "USD", vLocale));
        w.setHoldUsdFormatted(currencyService.formatIn(holdUsd, "USD", vLocale));
        return w;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletTransaction> getMyTransactions(UUID userId, int page, int size) {
        // Consultar el extracto NO abre el monedero. Antes se llamaba a walletOf, que lo crea: dentro de
        // una transacción de solo lectura Hibernate no vuelca el INSERT, así que el monedero no llegaba a
        // la base de datos pero sí se indexaba, y el buscador del panel enseñaba monederos fantasma.
        // Quien no tiene monedero no tiene movimientos, que es exactamente lo que hay que responder.
        Optional<Wallet> found = walletRepository.findByUserId(userId);
        if (found.isEmpty()) {
            return List.of();
        }
        Wallet w = found.get();
        List<WalletTransaction> txs = txRepository.findByWalletIdOrderByCreatedAtDesc(w.getId(), page,
                Math.min(size, 100));
        /*
         * Importes del libro mayor —que es USD canónico— CONVERTIDOS a la divisa activa, igual que el
         * saldo. Antes se formateaban siempre en dólares mientras el saldo de arriba sí se convertía:
         * en la misma pantalla, «£54.22» de saldo y «US$73.35» como saldo tras el último movimiento.
         * Dos cifras que son la misma y que no se parecen.
         */
        String currency = CurrencyHolder.get();
        String vLocale = currencyService.localeOf(currency);
        for (WalletTransaction t : txs) {
            BigDecimal amt = BigDecimal.valueOf(t.getAmountUsdCents()).divide(BigDecimal.valueOf(100), 4,
                    RoundingMode.HALF_UP);
            BigDecimal after = BigDecimal.valueOf(t.getBalanceAfterCents()).divide(BigDecimal.valueOf(100), 4,
                    RoundingMode.HALF_UP);
            String sign = t.getAmountUsdCents() >= 0 ? "+" : "-";
            t.setAmountFormatted(sign + currencyService.formatIn(currencyService.usdTo(amt.abs(), currency),
                    currency, vLocale));
            t.setBalanceAfterFormatted(
                    currencyService.formatIn(currencyService.usdTo(after, currency), currency, vLocale));
        }
        return txs;
    }

    @Override
    @Transactional(readOnly = true)
    public long countMyTransactions(UUID userId) {
        // Igual que el extracto: contar no puede crear el monedero (ver getMyTransactions).
        return walletRepository.findByUserId(userId)
                .map(w -> txRepository.countByWalletId(w.getId()))
                .orElse(0L);
    }

    /* ============ Admin ============ */

    @Override
    @Transactional
    public WalletTransaction adminTopup(UUID userId, long amountCents, String description, String idempotencyKey) {
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        String desc = description != null && !description.isBlank() ? description : "Admin manual top-up";
        return credit(userId, amountCents, null, key, desc);
    }

    @Override
    @Transactional
    public WalletTransaction adminAdjustEntry(UUID userId, long amountCents, String description,
            String idempotencyKey) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required for manual adjustments");
        }
        if (amountCents == 0) {
            throw new BusinessException("Adjustment amount cannot be zero");
        }
        // A manual adjustment can credit (+) or debit (-); recordTransaction() applies the signed amount
        // and rejects it if it would overdraw the wallet. (DROP-603: negative adjustments used to fail.)
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        return recordTransaction(
                new LedgerEntry(userId, "ADJUSTMENT", amountCents, null, null, key, "[Adjustment] " + description),
                null);
    }

    @Override
    @Transactional
    public Wallet adminGetWalletDetail(UUID userId) {
        Wallet w = walletOf(userId);
        w.setAvailableUsdCents(available(w));
        return w;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletTransaction> adminTransactions(UUID walletId, int page, int size) {
        return txRepository.findByWalletIdOrderByCreatedAtDesc(walletId, page, Math.min(size, 100));
    }

    @Override
    @Transactional(readOnly = true)
    public long countWalletTransactions(UUID walletId) {
        return txRepository.countByWalletId(walletId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Wallet> adminListWallets(String q, String status, String currency) {
        return filterWallets(q, status, currency);
    }

    private List<Wallet> filterWallets(String q, String status, String currency) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        return walletRepository.findAll().stream()
                .filter(w -> status == null || status.isBlank() || w.getStatus().equalsIgnoreCase(status))
                .filter(w -> currency == null || currency.isBlank()
                        || (w.getCurrencyDefault() != null && w.getCurrencyDefault().equalsIgnoreCase(currency)))
                .filter(w -> needle.isEmpty()
                        || (w.getUserEmail() != null && w.getUserEmail().toLowerCase().contains(needle))
                        || (w.getUserName() != null && w.getUserName().toLowerCase().contains(needle)))
                .sorted((a, b) -> Long.compare(b.getBalanceUsdCents(), a.getBalanceUsdCents())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WalletPage pageAdminWallets(String q, String status, String currency, int page, int size) {
        // La vía primaria es OpenSearch con el índice de wallets, que devuelve la página de identificadores
        // ya ordenada de más reciente a más antigua y filtrada. El saldo se lee siempre fresco de la base
        // de datos, porque es dinero y exige consistencia estricta: el índice solo pagina, ordena y filtra.
        Optional<WalletSearchService.IdPage> idx = walletSearchService.pageIds(q, status, currency, page, size);
        if (idx.isPresent()) {
            Map<UUID, Wallet> byId = new HashMap<>();
            walletRepository.findAll().forEach(w -> byId.put(w.getId(), w));
            List<Wallet> items = idx.get().ids().stream().map(byId::get).filter(Objects::nonNull).toList();
            return new WalletPage(items, page, size, idx.get().total());
        }
        List<Wallet> all = filterWallets(q, status, currency);
        int from = Math.min(Math.max(0, page) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new WalletPage(all.subList(from, to), page, size, all.size());
    }

    /* ============ Mutations ============ */

    @Override
    @Transactional
    public WalletTransaction deposit(UUID userId, long amountUsdCents, UUID paymentId, String idempotencyKey,
            String description) {
        return credit(userId, amountUsdCents, paymentId, idempotencyKey, description);
    }

    /** Abono al monedero; privado para que el alta manual del admin lo reutilice dentro de SU transacción. */
    private WalletTransaction credit(UUID userId, long amountUsdCents, UUID paymentId, String idempotencyKey,
            String description) {
        if (amountUsdCents <= 0)
            throw new BusinessException("Deposit amount must be positive");
        return recordTransaction(
                new LedgerEntry(userId, "DEPOSIT", amountUsdCents, paymentId, null, idempotencyKey, description),
                null);
    }

    @Override
    @Transactional
    public WalletTransaction charge(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey,
            String description) {
        if (amountUsdCents <= 0)
            throw new BusinessException("Charge amount must be positive");
        Wallet w = require(userId);
        if (available(w) < amountUsdCents) {
            throw new BusinessException("WALLET_INSUFFICIENT_BALANCE", "Insufficient wallet balance");
        }
        return recordTransaction(
                new LedgerEntry(userId, "PAYMENT", -amountUsdCents, null, orderId, idempotencyKey, description),
                null);
    }

    @Override
    @Transactional
    public WalletTransaction refund(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey,
            String reason) {
        return recordTransaction(
                new LedgerEntry(userId, "REFUND", amountUsdCents, null, orderId, idempotencyKey, reason), null);
    }

    @Override
    @Transactional
    public WalletTransaction hold(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey) {
        Wallet w = require(userId);
        if (available(w) < amountUsdCents)
            throw new BusinessException("WALLET_INSUFFICIENT_BALANCE", "Insufficient balance to hold");
        w.setHoldUsdCents(w.getHoldUsdCents() + amountUsdCents);
        w = walletRepository.save(w);
        return recordTransaction(
                new LedgerEntry(userId, "HOLD", -amountUsdCents, null, orderId, idempotencyKey, "Hold for order"), w);
    }

    @Override
    @Transactional
    public WalletTransaction release(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey) {
        Wallet w = require(userId);
        w.setHoldUsdCents(Math.max(0L, w.getHoldUsdCents() - amountUsdCents));
        w = walletRepository.save(w);
        return recordTransaction(
                new LedgerEntry(userId, "RELEASE", amountUsdCents, null, orderId, idempotencyKey, "Release hold"), w);
    }

    /* ============ Internals ============ */

    private long available(Wallet w) {
        return Math.max(0L, w.getBalanceUsdCents() - w.getHoldUsdCents());
    }

    private Wallet require(UUID userId) {
        return walletRepository.findByUserId(userId).orElseThrow(() -> new NotFoundException("Wallet not found"));
    }

    /** Carga la wallet con bloqueo de fila (FOR UPDATE) para los movimientos que tocan el saldo. */
    private Wallet requireForUpdate(UUID userId) {
        return walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
    }

    /**
     * Apunte del libro mayor antes de aplicarlo: qué movimiento es, por cuánto (con signo) y contra qué
     * documento (pago o pedido) va. La clave de idempotencia viaja con el apunte porque es lo que impide
     * abonar dos veces el mismo webhook.
     */
    private record LedgerEntry(UUID userId, String kind, long signedAmount, UUID paymentId, UUID orderId,
            String idempotencyKey, String description) {
    }

    /**
     * Escribe el apunte en el libro mayor. {@code preloadedWallet} evita releer la wallet cuando quien
     * llama ya la ha modificado (HOLD/RELEASE): releerla descartaría ese cambio pendiente.
     */
    private WalletTransaction recordTransaction(LedgerEntry entry, Wallet preloadedWallet) {
        // For HOLD/RELEASE we do NOT change balance, only hold counter (signedAmount is informational).
        boolean affectsBalance = !"HOLD".equals(entry.kind()) && !"RELEASE".equals(entry.kind());
        // Movimientos que tocan el saldo: cargar la wallet con BLOQUEO de fila (FOR UPDATE) para que la
        // lectura del saldo, la comprobación y la escritura queden serializadas y NO se pueda doble-gastar
        // por checkouts concurrentes (lost update). El lock también serializa reenvíos de la MISMA clave de
        // idempotencia, por eso su comprobación va AHORA después de tomar el lock.
        Wallet w = preloadedWallet != null ? preloadedWallet
                : (affectsBalance ? requireForUpdate(entry.userId()) : require(entry.userId()));
        if (entry.idempotencyKey() != null) {
            Optional<WalletTransaction> existing = txRepository.findByIdempotencyKey(entry.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Returning existing wallet tx for idempotency-key={}", entry.idempotencyKey());
                return existing.get();
            }
        }
        long newBalance = w.getBalanceUsdCents();
        if (affectsBalance) {
            // El saldo se mueve con UNA sentencia atómica que lleva la condición dentro ("no quedes en
            // negativo"). Antes se leía el saldo, se comprobaba en Java y se guardaba: ocho checkouts
            // simultáneos pasaban los ocho la comprobación sobre la misma lectura y cuatro pedidos
            // quedaban PAID con un único débito (pentest del 14-ago-2026). Comprobado también que el
            // bloqueo pesimista de la lectura no bastaba para impedirlo.
            if (!walletRepository.applyBalanceDelta(entry.userId(), entry.signedAmount())) {
                throw new BusinessException("Wallet balance would go negative");
            }
            newBalance = walletRepository.currentBalanceCents(entry.userId());
            w.setBalanceUsdCents(newBalance);
        }
        WalletTransaction tx = WalletTransaction.builder().walletId(w.getId()).kind(entry.kind())
                .amountUsdCents(entry.signedAmount()).balanceAfterCents(newBalance).paymentId(entry.paymentId())
                .orderId(entry.orderId()).idempotencyKey(entry.idempotencyKey()).status("COMPLETED")
                .description(entry.description()).build();
        WalletTransaction saved = txRepository.save(tx);
        auditLogger.log("wallet." + entry.kind().toLowerCase(), String.valueOf(entry.userId()),
                Map.of("amount", entry.signedAmount(), "balance_after", newBalance, "tx_id", saved.getId()));
        return saved;
    }
}
