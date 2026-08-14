package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import java.util.Map.Entry;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads currency rates from the DB with a small in-memory cache (5 min TTL) to keep the
 * hot path of price conversion zero-IO. All amounts in the system are persisted in USD;
 * this service converts to the user-selected currency at serve time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyRateService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final CurrencyRateRepository repository;

    private volatile Instant cacheStamp = Instant.EPOCH;
    private final Map<String, CurrencyRateEntity> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void warm() {
        refreshCache();
    }

    /* ============ Reads ============ */

    public List<CurrencyRateEntity> listActive() {
        ensureFresh();
        return cache.values().stream().filter(CurrencyRateEntity::isActive)
                .sorted((a, b) -> a.getCode().compareTo(b.getCode())).toList();
    }

    /** Todas las monedas (activas e inactivas), ordenadas por código — para la pantalla de admin. */
    public List<CurrencyRateEntity> listAll() {
        ensureFresh();
        return cache.values().stream().sorted((a, b) -> a.getCode().compareTo(b.getCode())).toList();
    }

    public Optional<CurrencyRateEntity> find(String code) {
        if (code == null)
            return Optional.empty();
        ensureFresh();
        return Optional.ofNullable(cache.get(code.toUpperCase(Locale.ROOT)));
    }

    public CurrencyRateEntity require(String code) {
        return find(code).orElseThrow(() -> new NotFoundException("Currency not found: " + code));
    }

    /* ============ Conversion ============ */

    /** Convert an amount in USD to the active display currency (from CurrencyHolder). */
    public BigDecimal usdToDisplay(BigDecimal amountUsd) {
        return usdTo(amountUsd, CurrencyHolder.get());
    }

    public BigDecimal usdTo(BigDecimal amountUsd, String targetCode) {
        if (amountUsd == null)
            return null;
        // Precio final al cliente, redondeado a la unidad MÁS PEQUEÑA QUE EXISTE en esa moneda y al valor
        // más cercano (HALF_UP). Antes se redondeaba siempre hacia arriba (UP), lo que inflaba montos
        // exactos en el round-trip de moneda (30 CNY → USD → 30.01 CNY); con HALF_UP un monto exacto de
        // origen se muestra exacto.
        //
        // Y antes se fijaban siempre 2 decimales, tuviera la moneda o no. El yen y el peso chileno no
        // tienen céntimos: el importe se guardaba como 2775,53 ¥ y al pintarlo salía «2.776 ¥», así que
        // el cliente veía 2.776 ¥ la unidad y 11.102 ¥ por cuatro —dos yenes que no cuadran por más que
        // multiplique—. Redondeando aquí a los decimales reales de la moneda, lo que se enseña coincide
        // con lo que suma.
        int decimales = fractionDigits(targetCode);
        if ("USD".equalsIgnoreCase(targetCode)) {
            return amountUsd.setScale(decimales, RoundingMode.HALF_UP);
        }
        return find(targetCode)
                .map(r -> amountUsd.multiply(r.getRateVsUsd()).setScale(decimales, RoundingMode.HALF_UP))
                .orElse(amountUsd.setScale(decimales, RoundingMode.HALF_UP));
    }

    /**
     * Decimales que admite una moneda: 0 en yen o peso chileno, 2 en la mayoría, 3 en el dinar kuwaití.
     * Lo dice la propia JDK a partir de la ISO 4217; para un código que no reconozca se asumen 2, que es
     * lo más común y lo que se hacía antes para todas.
     */
    /** Decimales que admite una moneda, para quien necesite redondear igual que las conversiones. */
    public int decimalsOf(String code) {
        return fractionDigits(code);
    }

    private static int fractionDigits(String code) {
        if (code == null || code.isBlank()) {
            return 2;
        }
        try {
            int digits = Currency.getInstance(code.toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
            return digits >= 0 ? digits : 2;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /** Convert an amount in any source currency to USD (used at order creation to fix USD canonical). */
    public BigDecimal toUsd(BigDecimal amount, String sourceCode) {
        if (amount == null)
            return null;
        if ("USD".equalsIgnoreCase(sourceCode))
            return amount.setScale(4, RoundingMode.HALF_UP);
        // Falta la tasa de la divisa en la que está guardado el producto: eso NO es «recurso no
        // encontrado», es que al servidor le falta configuración. Lanzaba NotFoundException, que el
        // manejador traduce a 404, así que el catálogo entero respondía «no existe» cuando lo que pasaba
        // es que no había tasa de CNY — y ni el cliente ni el panel podían saber por qué. Como error de
        // estado sale con 500 y queda registrado con log.error, que es donde tiene que verse.
        return find(sourceCode).map(r -> amount.divide(r.getRateVsUsd(), 4, RoundingMode.HALF_UP))
                .orElseThrow(() -> new IllegalStateException(
                        "Falta la tasa de cambio de la divisa de origen '" + sourceCode
                                + "': revisa la tabla currency_rate"));
    }

    public String symbolOf(String code) {
        return find(code).map(CurrencyRateEntity::getSymbol).orElse("$");
    }

    public String localeOf(String code) {
        return find(code).map(CurrencyRateEntity::getLocale).orElse("en-US");
    }

    /**
     * Formatea un importe (ya en la moneda de display) a string localizado según el `locale` de la
     * moneda en BD: "28,26 €" (es-ES/EUR), "$32.56" (en-US/USD), "¥220.03" (zh-CN/CNY). El frontend
     * SOLO PINTA este string; ningún cálculo ni formateo de precio vive en el cliente. El importe ya
     * viene redondeado (2 dec UP) desde el pipeline de precios; aquí solo se le da forma textual.
     */
    public String formatDisplay(BigDecimal amountDisplay, String code) {
        return formatDisplay(amountDisplay, code, false);
    }

    /**
     * Formatea {@code amount} en la moneda {@code code} usando los separadores de miles/decimales del
     * {@code localeTag} indicado (la convención del país que MIRA), no la de la moneda. Así un usuario que
     * trabaja en español ve tanto EUR como USD con coma decimal y punto de miles ("1.234,56 €", "1.234,56 US$"),
     * igual que Stripe. El símbolo lo pone la moneda; los separadores, el locale del visor.
     */
    public String formatIn(BigDecimal amount, String code, String localeTag) {
        if (amount == null || code == null) {
            return null;
        }
        Locale locale = Locale.forLanguageTag(localeTag != null && !localeTag.isBlank() ? localeTag : localeOf(code));
        try {
            java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(locale);
            nf.setCurrency(Currency.getInstance(code.toUpperCase(Locale.ROOT)));
            return nf.format(amount);
        } catch (RuntimeException nonIsoOrUnknown) {
            return symbolOf(code) + " " + amount.toPlainString();
        }
    }

    /**
     * Variante que REDONDEA a número entero (HALF_UP: ≥0.5 arriba, &lt;0.5 abajo) y formatea SIN decimales.
     * Pensada para los precios de PLANES, que se muestran redondeados (p.ej. "25 €" en vez de "25,28 €").
     */
    public String formatDisplayRounded(BigDecimal amountDisplay, String code) {
        return formatDisplay(amountDisplay, code, true);
    }

    private String formatDisplay(BigDecimal amountDisplay, String code, boolean wholeNumber) {
        if (amountDisplay == null || code == null) {
            return null;
        }
        BigDecimal amount = wholeNumber ? amountDisplay.setScale(0, RoundingMode.HALF_UP) : amountDisplay;
        Locale locale = Locale.forLanguageTag(localeOf(code));
        try {
            java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(locale);
            nf.setCurrency(Currency.getInstance(code.toUpperCase(Locale.ROOT)));
            if (wholeNumber) {
                nf.setMaximumFractionDigits(0);
                nf.setMinimumFractionDigits(0);
            }
            return nf.format(amount);
        } catch (RuntimeException nonIsoOrUnknown) {
            // Códigos no ISO (p.ej. USDT) o locale inválido: símbolo de BD + número plano.
            return symbolOf(code) + " " + amount.toPlainString();
        }
    }

    /* ============ Admin ============ */

    @Transactional
    public CurrencyRateEntity setActive(String code, boolean active) {
        CurrencyRateEntity r = repository.findByCodeIgnoreCase(code).orElseThrow(() -> new NotFoundException(code));
        r.setActive(active);
        repository.save(r);
        refreshCache();
        return r;
    }

    @Transactional
    public CurrencyRateEntity overrideRate(String code, BigDecimal newRate) {
        CurrencyRateEntity r = repository.findByCodeIgnoreCase(code).orElseThrow(() -> new NotFoundException(code));
        r.setRateVsUsd(newRate);
        r.setLastSyncedAt(Instant.now());
        repository.save(r);
        refreshCache();
        return r;
    }

    @Transactional
    public void applyBulkSync(Map<String, BigDecimal> ratesFromProvider) {
        if (ratesFromProvider == null || ratesFromProvider.isEmpty()) {
            log.warn("Currency sync: empty payload, keeping current cache");
            return;
        }
        Instant now = Instant.now();
        int updated = 0;
        int created = 0;
        for (Entry<String,BigDecimal> entry : ratesFromProvider.entrySet()) {
            // Locale.ROOT: con la configuración regional turca "tr", toUpperCase() convierte la i en İ y
            // "try" saldría como "TRY" con punto, creando una moneda distinta de la que ya existe.
            String code = entry.getKey() == null ? null : entry.getKey().toUpperCase(Locale.ROOT);
            if (code == null || code.length() != 3) {
                continue; // ignorar metales/cripto u otros códigos no ISO de 3 letras
            }
            CurrencyRateEntity existing = repository.findByCodeIgnoreCase(code).orElse(null);
            if (existing == null) {
                // Moneda nueva del proveedor: se PERSISTE pero INACTIVA por defecto (solo las que usa la
                // app quedan activas). El admin puede activarla desde la pantalla de monedas.
                existing = CurrencyRateEntity.builder().code(code).name(CurrencySymbols.nameFor(code))
                        .symbol(CurrencySymbols.symbolFor(code)).locale("en-US").rateVsUsd(entry.getValue())
                        .active(false).lastSyncedAt(now).build();
                created++;
            } else {
                existing.setRateVsUsd(entry.getValue());
                existing.setLastSyncedAt(now);
                updated++;
            }
            repository.save(existing);
        }
        refreshCache();
        log.info("Currency sync: {} actualizadas, {} nuevas persistidas (inactivas) desde el proveedor", updated,
                created);
    }

    /* ============ cache plumbing ============ */

    /**
     * Marca la caché en memoria como caducada para que la SIGUIENTE lectura la recargue de la base.
     *
     * <p>Hacía falta una puerta como esta porque este mapa NO lo gobierna el {@code CacheManager} de
     * Spring: es una caché propia con una ventana de cinco minutos, y hasta ahora solo la refrescaban las
     * escrituras que pasan por este mismo servicio ({@link #setActive}, {@link #overrideRate},
     * {@link #applyBulkSync}). Cuando la tabla de tasas cambia por CUALQUIER otra vía —una migración, una
     * carga por SQL, otra instancia del proceso o el vaciado de tablas de una prueba de integración— el
     * proceso sigue sirviendo las tasas viejas hasta que vence la ventana, sin forma de forzar la
     * relectura. Peor: si el vencimiento cae en un instante en el que la tabla está vacía, la recarga deja
     * la caché VACÍA y con sello nuevo, así que durante los cinco minutos siguientes cualquier conversión
     * desde yuanes revienta con «Unknown source currency: CNY» (un 404 en toda la lectura del catálogo).
     * Con esta llamada, quien sabe que ha tocado las tasas por fuera puede dejar la caché consistente.
     */
    public void invalidateCache() {
        cacheStamp = Instant.EPOCH;
    }

    private synchronized void refreshCache() {
        cache.clear();
        repository.findAll().forEach(r -> cache.put(r.getCode().toUpperCase(Locale.ROOT), r));
        cacheStamp = Instant.now();
    }

    private void ensureFresh() {
        if (Duration.between(cacheStamp, Instant.now()).compareTo(CACHE_TTL) >= 0) {
            refreshCache();
        }
    }

    /** Quick helper for formatting in admin endpoints (best-effort). */
    public BigDecimal asPercentage(BigDecimal value) {
        return value == null ? null : value.divide(HUNDRED, 4, RoundingMode.HALF_UP);
    }
}
