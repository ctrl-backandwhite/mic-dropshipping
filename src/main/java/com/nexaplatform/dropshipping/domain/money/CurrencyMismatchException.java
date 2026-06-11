package com.nexaplatform.dropshipping.domain.money;

/**
 * Excepción de dominio: se intentó operar dos {@link Money} de divisas distintas.
 * Es un bug que debe propagarse para que el llamante haga una conversión
 * explícita con tasa antes de seguir. Nunca silenciamos esta condición.
 */
public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected " + expected + " but got " + actual);
    }
}
