package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * USDT (Tether) deposit. Two operating modes:
 *
 * <ol>
 *  <li><b>Manual</b> (default): operator publishes a static TRC-20 deposit address in
 *  {@code NEXADROP_USDT_DEPOSIT_ADDRESS}. The frontend shows the address + QR. The buyer
 *  sends USDT, then clicks "I've sent the payment" → admin manually confirms by polling
 *  the blockchain (or via {@code /api/admin/payments/{id}/confirm} as fallback).</li>
 *  <li><b>Coinbase Commerce</b> (when {@code NEXADROP_COINBASE_API_KEY} is set): each
 *  payment gets a unique hosted charge with on-chain confirmation via webhook.</li>
 * </ol>
 *
 * USDT settles 1:1 to USD; we credit the wallet with the USD-equivalent.
 */
@Slf4j
@Component
public class UsdtGateway implements PaymentGateway {

    @Value("${nexadrop.usdt.enabled:true}")
    private boolean enabled;
    // Sin dirección por defecto: si NEXADROP_USDT_MANUAL_ADDRESS no se define, USDT manual NO opera (antes
    // había una dirección TRON incrustada y comiteada, y todos los depósitos iban ahí si faltaba el env).
    @Value("${nexadrop.usdt.manual-address:}")
    private String manualAddress;
    @Value("${nexadrop.usdt.chain:TRC20}")
    private String defaultChain;
    @Value("${nexadrop.usdt.expiry-minutes:30}")
    private int expiryMinutes;
    @Value("${nexadrop.coinbase.api-key:}")
    private String coinbaseKey;

    @Override
    public boolean supports(PaymentMethod m) {
        return m == PaymentMethod.USDT;
    }

    @Override
    public String providerName() {
        return coinbaseKey.isBlank() ? "manual" : "coinbase";
    }

    @Override
    public InitiateResult initiate(PaymentEntity p) {
        // Manual mode for now — Coinbase Commerce can be plugged in by extending this method.
        // FAIL-CLOSED: sin dirección de depósito configurada (ni Coinbase), NO se puede iniciar un pago USDT.
        // Evita dirigir los fondos del cliente a una dirección por defecto/incrustada.
        if (coinbaseKey.isBlank() && (manualAddress == null || manualAddress.isBlank())) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException("USDT_NOT_CONFIGURED",
                    "El pago con USDT no está disponible en este momento.");
        }
        String address = manualAddress;
        String chain = defaultChain;
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(expiryMinutes));
        String qr = "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=" + address;
        log.info("USDT manual deposit initiated for payment {} ({} chain)", p.getId(), chain);
        return new InitiateResult("usdt_" + p.getId(), null, null, address, chain, qr,
                Map.of("manual", true, "address", address, "chain", chain, "expiresAt", expiresAt.toString()));
    }

    @Override
    public ConfirmResult confirm(PaymentEntity p, Map<String, Object> providerPayload) {
        // For manual mode, an admin endpoint will explicitly confirm. For Coinbase webhooks,
        // we trust the validated payload with status == COMPLETED/CONFIRMED.
        Object eventStatus = providerPayload.getOrDefault("status", "");
        boolean ok = "COMPLETED".equalsIgnoreCase(eventStatus.toString())
                || "CONFIRMED".equalsIgnoreCase(eventStatus.toString());
        return new ConfirmResult(ok, ok ? null : "USDT not yet confirmed", providerPayload);
    }
}
