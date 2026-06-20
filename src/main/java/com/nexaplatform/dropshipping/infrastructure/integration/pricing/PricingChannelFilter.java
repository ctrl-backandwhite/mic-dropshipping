package com.nexaplatform.dropshipping.infrastructure.integration.pricing;

import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
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
 * Marca el canal de precios como {@link PriceRuleChannel#INTEGRATION} para las peticiones de apps
 * conectadas por API/integración (Shopify, WooCommerce, API de partners). Así {@code MarginService}
 * aplica el margen del canal INTEGRATION (75%) en vez del de STOREFRONT (150%). El storefront/web no
 * pasa por aquí y mantiene su canal por defecto (STOREFRONT). Análogo a {@code CurrencyRequestFilter}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 31)
public class PricingChannelFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean integration = path != null
                && (path.startsWith("/api/v1/partner/") || path.startsWith("/api/v1/integrations/"));
        try {
            if (integration) {
                PricingChannelHolder.set(PriceRuleChannel.INTEGRATION);
            }
            chain.doFilter(req, res);
        } finally {
            if (integration) {
                PricingChannelHolder.clear();
            }
        }
    }
}
