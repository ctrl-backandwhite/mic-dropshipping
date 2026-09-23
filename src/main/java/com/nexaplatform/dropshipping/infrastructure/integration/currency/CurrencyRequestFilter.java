package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the {@code X-Currency} request header and populates {@link CurrencyHolder}.
 * Order is HIGH_PRECEDENCE + 30 so this runs after security headers / rate limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class CurrencyRequestFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Currency";

    private final CurrencyRateService currencyRateService;

    public CurrencyRequestFilter(CurrencyRateService currencyRateService) {
        this.currencyRateService = currencyRateService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            CurrencyHolder.set(divisaAceptable(req.getHeader(HEADER)));
            chain.doFilter(req, res);
        } finally {
            CurrencyHolder.clear();
        }
    }

    /**
     * Acepta la divisa pedida SOLO si existe y está activa; en cualquier otro caso devuelve {@code null},
     * que deja el importe en USD.
     *
     * <p>Antes se admitía la cabecera tal cual, y eso producía precios que mentían: con {@code CHF} (sin
     * tasa sembrada) la conversión devolvía el importe en dólares SIN convertir, pero el formateador
     * reconocía CHF como código ISO y pintaba «CHF 20.00» — al cliente se le enseñaban francos que eran
     * dólares. Y una divisa DESACTIVADA, fuera del selector del escaparate, seguía convirtiendo si se
     * pedía por cabecera. Validar aquí deja el importe y su etiqueta siempre en la misma moneda, que es
     * lo único que el cliente puede interpretar sin equivocarse.
     */
    private String divisaAceptable(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        return currencyRateService.find(codigo).filter(CurrencyRateEntity::isActive).map(CurrencyRateEntity::getCode)
                .orElse(null);
    }
}
