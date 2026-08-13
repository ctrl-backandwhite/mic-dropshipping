package com.nexaplatform.dropshipping.api.dto.out;

import java.util.UUID;

/**
 * Resultado de iniciar un cobro con tarjeta guardada (off-session).
 * <ul>
 *   <li>{@code succeeded}: cobro completado; el pedido queda PAGADO.</li>
 *   <li>{@code requires_action}: la tarjeta exige 3DS; el navegador autentica con {@code clientSecret} y luego
 *       llama a confirm.</li>
 * </ul>
 */
public record SavedCardPayDtoOut(String status, String clientSecret, UUID paymentId) {
}
