package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Currency;
import java.util.Locale;

/**
 * Divisa que le corresponde a un país.
 *
 * <p><b>Por qué existe.</b> Esta lógica vivía metida dentro de {@code GeoController}, y por eso solo la
 * usaba quien llegaba por una petición HTTP. Los correos se generan en segundo plano, sin petición y sin
 * cabecera {@code X-Currency}, así que caían al valor por defecto: un usuario dado de alta en España
 * recibía la campaña de novedades con los precios en DÓLARES. El precio en una divisa que no es la suya no
 * es solo incómodo — es una cifra que no puede comparar con lo que verá al entrar en la tienda.
 *
 * <p>La divisa sale del propio país (ISO 4217 vía {@link Currency}) y solo se usa si la plataforma la tiene
 * activa; en cualquier otro caso, dólares. Nunca lanza: un país desconocido, mal escrito o sin divisa
 * asociada cae en el valor por defecto en vez de romper el envío de un correo.
 */
@Service
@RequiredArgsConstructor
public class CountryCurrencyService {

    private static final String DEFAULT = "USD";

    private final CurrencyRateService currencyRateService;

    /**
     * Código de divisa para el país dado, o {@code USD} si no se puede resolver o no está activa.
     *
     * @param country código ISO-3166 alfa-2 ({@code ES}, {@code DE}…); tolera nulo, espacios y minúsculas.
     */
    public String forCountry(String country) {
        if (country == null || country.isBlank()) {
            return DEFAULT;
        }
        String iso = country.trim().toUpperCase();
        // Se exige ISO-2 exacto: así un valor arbitrario que llegue de una cabecera o de un perfil mal
        // rellenado no acaba consultando divisas inventadas.
        if (iso.length() != 2 || !iso.chars().allMatch(Character::isLetter)) {
            return DEFAULT;
        }
        try {
            String code = Currency.getInstance(Locale.of("", iso)).getCurrencyCode();
            return currencyRateService.find(code).filter(CurrencyRateEntity::isActive).isPresent()
                    ? code : DEFAULT;
        } catch (IllegalArgumentException ex) {
            return DEFAULT;
        }
    }
}
