package com.nexaplatform.dropshipping.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Escenario compartido por las pruebas del CICLO DE VIDA DEL PEDIDO: siembra un catálogo mínimo y
 * determinista, crea compradores con saldo y ofrece atajos HTTP para las transiciones del pedido.
 *
 * <p><b>Por qué un escenario sembrado a mano y no los datos reales.</b> Todo lo que entra en el importe
 * del pedido —precio, margen, porte, impuesto y despacho de aduana— se fija aquí a valores cerrados para
 * que el total se pueda calcular A MANO y comprobarse al céntimo. Un 200 con el número equivocado es un
 * fallo, y sin números controlados no hay forma de distinguirlo de un acierto.
 *
 * <p><b>Por qué el producto se tarifa en USD y no en CNY.</b> {@code CurrencyRateService} cachea la tabla
 * de divisas al arrancar el contexto y solo la refresca cada cinco minutos, así que una fila insertada
 * por SQL en mitad de la prueba NO se vería y la conversión CNY→USD reventaría. Con la divisa de origen
 * en USD la conversión es la identidad y no toca la caché. Ojo: la columna {@code cost_cny_cents} del
 * pedido sigue llamándose CNY y guarda ese mismo importe — es la base de la comisión del operador.
 *
 * <p>Por el mismo motivo NO se siembra ninguna regla de margen: {@code MarginService} también cachea, y
 * sin regla aplicable el precio de venta es el coste, que es el único valor estable entre pruebas.
 */
abstract class OrderLifecycleSupport extends BaseIntegration {

    @Autowired
    private MarginService marginService;

    @Autowired
    private CurrencyRateService divisas;

    /**
     * Vacía la caché de reglas de margen ANTES de cada prueba.
     *
     * <p>El javadoc de arriba dice que no se siembra ninguna regla «porque MarginService también cachea»,
     * pero no bastaba con no sembrarla: la caché se calienta al ARRANCAR el contexto, antes de que
     * BaseIntegration vacíe las tablas, así que la regla GLOBAL/STOREFRONT que trae Liquibase sobrevivía
     * dentro de la caché al TRUNCATE y seguía aplicando su margen a cada línea. El resultado eran importes
     * marcados donde la prueba esperaba el coste — y lo que fallaba era justo lo que compara importes
     * absolutos. CheckoutFlowIT ya caía en esta trampa y la resuelve igual.
     */
    @BeforeEach
    void vaciarCacheDeMargenes() {
        marginService.invalidateCache();
        refrescarDivisas();
    }

    /**
     * Repone las divisas que necesita cualquier pedido, sin depender de qué prueba corrió antes.
     *
     * <p>{@code cleanAllTables()} vacía también {@code currency_rate}, así que cada prueba tiene que
     * sembrar las suyas. Sin esto, el derecho fijo de la Unión —tres euros por partida— no encuentra la
     * tasa del euro y crear el pedido devuelve un 500. Funcionaba de casualidad cuando una prueba de
     * aduanas había corrido antes en la misma JVM y había dejado la tasa en la caché de cinco minutos:
     * una prueba que depende del orden de ejecución falla el día que ese orden cambia, y lo hace con un
     * error que no señala a la causa.
     *
     * <p>Se insertan por SQL y no con {@code overrideRate}, que exige que la fila ya exista y falla
     * justo después del vaciado. Paridad 1:1 a propósito: las pruebas de esta jerarquía comparan
     * importes absolutos y miden cuánto se devuelve, no cuánto vale una divisa; la conversión con tasas
     * reales está certificada aparte, en {@code CustomsDutyIT}.
     */
    private void refrescarDivisas() {
        jdbcTemplate.update("""
                INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active, last_synced_at)
                VALUES (gen_random_uuid(), 'USD', 'US Dollar', '$', 'en-US', 1, true, now()),
                       (gen_random_uuid(), 'EUR', 'Euro', '€', 'es-ES', 1, true, now())
                ON CONFLICT (code) DO UPDATE SET rate_vs_usd = EXCLUDED.rate_vs_usd, active = true""");
        divisas.invalidateCache();
    }

    /** Cuerpo JSON genérico de las respuestas que hay que leer campo a campo. */
    protected static final ParameterizedTypeReference<Map<String, Object>> MAPA =
            new ParameterizedTypeReference<Map<String, Object>>() {
            };

    /** Destino de todos los pedidos de la prueba: es el único país con cobertura sembrada. */
    protected static final String PAIS = "ES";

    /** Porte plano: la zona se siembra con base 5,00 $ y 0 por kg, así el peso no altera el total. */
    protected static final int ENVIO_CENTS = 500;

    /** IVA del destino en puntos básicos (21% de España). */
    protected static final int IVA_BPS = 2100;

    /** Precio de proveedor del producto sembrado, en céntimos (25,00). Sin regla de margen, es el PVP. */
    protected static final int PRECIO_UNITARIO_CENTS = 2500;

    /** Saldo de partida del comprador: holgado para varios pedidos seguidos. */
    protected static final long SALDO_INICIAL_CENTS = 50_000L;

    protected static final String CHECKOUT = "/api/me/orders/checkout";
    protected static final String ADMIN_ORDERS = "/api/admin/orders/";

    /**
     * Total del pedido calculado A MANO, en céntimos: producto + porte + IVA sobre (producto + porte).
     *
     * <p>Los importes están elegidos para que el 21% caiga exacto y no haya que discutir el redondeo:
     * 1 unidad → 2500 + 500 = 3000, IVA 630, total 3630. 2 unidades → 5000 + 500 = 5500, IVA 1155,
     * total 6655. Si el backend devuelve otra cifra, o el reembolso abona otra, es un fallo de dinero.
     */
    protected static int totalEsperadoCents(int cantidad) {
        int subtotal = PRECIO_UNITARIO_CENTS * cantidad;
        int baseImponible = subtotal + ENVIO_CENTS;
        int iva = baseImponible * IVA_BPS / 10_000;
        return subtotal + ENVIO_CENTS + iva;
    }

    /* ==================== Siembra del escenario ==================== */

    /**
     * Deja el entorno listo para comprar: cobertura de transporte del destino, IVA del destino y un
     * producto activo con una variante comprable. Devuelve el id del producto.
     */
    protected UUID sembrarEscenarioDeCompra() {
        habilitarEnvio(PAIS, ENVIO_CENTS);
        fijarIva(PAIS, IVA_BPS);
        return crearProductoActivo();
    }

    /** Cobertura del transportista para el destino: sin fila, el checkout rechaza el país. */
    protected void habilitarEnvio(String pais, int baseCents) {
        jdbcTemplate.update("INSERT INTO cainiao_shipping_zone (id, country_code, country_name, zone,"
                + " base_cents, per_kg_cents, eta_min_days, eta_max_days, enabled, created_at)"
                + " VALUES (?, ?, ?, 'EU', ?, 0, 8, 18, true, now())",
                UUID.randomUUID(), pais, "Pais de prueba", baseCents);
    }

    /** Tasa nacional del destino. Se lee sin caché, así que basta con insertarla. */
    protected void fijarIva(String pais, int bps) {
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active,"
                + " created_at, updated_at) VALUES (?, ?, 'IVA', ?, true, now(), now())",
                UUID.randomUUID(), pais, bps);
    }

    /** Producto ACTIVO con proveedor (para la cola de compras), traducción y una variante comprable. */
    protected UUID crearProductoActivo() {
        UUID proveedorId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO supplier (id, external_id, source, name, created_at, updated_at)"
                + " VALUES (?, ?, '1688', 'Proveedor de prueba', now(), now())",
                proveedorId, "sup-" + proveedorId);

        UUID productoId = UUID.randomUUID();
        // La divisa es USD a propósito (ver javadoc de la clase): la conversión es la identidad y el
        // precio de venta queda clavado en PRECIO_UNITARIO_CENTS sin depender de cachés.
        // Lleva partida arancelaria y peso porque el pedido no se despacha sin los datos con los que se
        // declara en aduana: un producto sin ellos ya no pasa de «pagado», que es justo lo que este
        // recorrido no viene a comprobar.
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, title_zh, moq,"
                + " base_price, currency, status, weight_grams, hs_code, created_at, updated_at)"
                + " VALUES (?, ?, ?, '1688', ?, '测试商品', 1, ?, 'USD', 'ACTIVE', 500, '6109100000', now(), now())",
                productoId, "producto-ciclo-" + productoId, "ext-" + productoId, proveedorId,
                BigDecimal.valueOf(PRECIO_UNITARIO_CENTS, 2));

        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, created_at,"
                + " updated_at) VALUES (?, ?, 'es', 'Producto de prueba', now(), now())",
                UUID.randomUUID(), productoId);

        // El nombre en inglés es el EName de la declaración: sin él la guía no se puede emitir.
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, created_at,"
                + " updated_at) VALUES (?, ?, 'en', 'Test product', now(), now())",
                UUID.randomUUID(), productoId);

        // Misma tarifa que la base: el precio del pedido es idéntico se compre con variante o sin ella,
        // y el stock inicial sirve para comprobar la política de inventario del dropshipping.
        jdbcTemplate.update("INSERT INTO product_variant (id, product_id, sku, title, price, stock, active,"
                + " created_at, updated_at) VALUES (?, ?, ?, 'Talla U', ?, 40, true, now(), now())",
                UUID.randomUUID(), productoId, "SKU-" + productoId,
                BigDecimal.valueOf(PRECIO_UNITARIO_CENTS, 2));
        return productoId;
    }

    /** Alta mínima de usuario. El rol de la fila importa poco: la autorización va por el rol del JWT. */
    protected UUID crearUsuario(String rol) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, ?, true, 'es', now(), now())", id, correoDe(id), rol);
        return id;
    }

    protected String correoDe(UUID userId) {
        return "ciclo-" + userId + "@nx036.local";
    }

    /**
     * Acredita saldo escribiendo la fila del monedero: la recarga real exige pasarela de pago y aquí lo
     * que se prueba es el pedido, no el cobro de la recarga.
     */
    protected void darSaldo(UUID userId, long cents) {
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents,"
                + " currency_default, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 0, 'USD', 'ACTIVE', now(), now())", UUID.randomUUID(), userId, cents);
    }

    /** Comprador listo para pagar: usuario con saldo. Devuelve su id. */
    protected UUID crearCompradorConSaldo() {
        UUID userId = crearUsuario("USER");
        darSaldo(userId, SALDO_INICIAL_CENTS);
        return userId;
    }

    protected String tokenDe(UUID userId, String rol) {
        return jwt.userToken(userId, correoDe(userId), rol);
    }

    /* ==================== Lecturas de la base ==================== */

    protected long saldoDe(UUID userId) {
        Long saldo = jdbcTemplate.queryForObject("SELECT balance_usd_cents FROM wallet WHERE user_id = ?",
                Long.class, userId);
        return saldo == null ? 0L : saldo;
    }

    protected String estadoDe(UUID pedidoId) {
        return jdbcTemplate.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class,
                pedidoId);
    }

    protected int totalDe(UUID pedidoId) {
        Integer total = jdbcTemplate.queryForObject("SELECT total_cents FROM customer_order WHERE id = ?",
                Integer.class, pedidoId);
        return total == null ? 0 : total;
    }

    /** Fuerza el estado del pedido en la base para poder atacar transiciones que ningún endpoint produce. */
    protected void forzarEstado(UUID pedidoId, String estado) {
        jdbcTemplate.update("UPDATE customer_order SET status = ? WHERE id = ?", estado, pedidoId);
    }

    /** Nº de abonos al monedero por el concepto dado (el prefijo de la clave de idempotencia). */
    protected int abonosCon(UUID pedidoId, String prefijoClave) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transaction"
                + " WHERE idempotency_key = ?", Integer.class, prefijoClave + pedidoId);
        return n == null ? 0 : n;
    }

    /** Suma de TODOS los movimientos del monedero de un usuario (para cuadrar el libro con el saldo). */
    protected long sumaMovimientos(UUID userId) {
        Long suma = jdbcTemplate.queryForObject("SELECT coalesce(sum(t.amount_usd_cents), 0)"
                + " FROM wallet_transaction t JOIN wallet w ON w.id = t.wallet_id WHERE w.user_id = ?",
                Long.class, userId);
        return suma == null ? 0L : suma;
    }

    /* ==================== Llamadas HTTP ==================== */

    /**
     * Checkout del comprador. {@code metodo} = WALLET cobra en el acto y deja el pedido PAID; cualquier
     * otro (CARD/PAYPAL) lo deja PENDING a la espera del pago externo.
     */
    protected Map<String, Object> checkout(UUID userId, UUID productoId, int cantidad, String metodo,
            String claveIdem) {
        return checkout(userId, productoId, null, cantidad, metodo, claveIdem);
    }

    /**
     * Checkout indicando además la VARIANTE comprada. La variante sembrada tiene el mismo precio que la
     * base, así que el importe no cambia; lo que cambia es que la línea queda ligada a un SKU concreto,
     * que es la única forma de observar si el inventario se mueve.
     */
    protected Map<String, Object> checkout(UUID userId, UUID productoId, UUID varianteId, int cantidad,
            String metodo, String claveIdem) {
        Map<String, Object> direccion = new LinkedHashMap<>();
        direccion.put("fullName", "Cliente Ciclo");
        direccion.put("phone", "+34600000001");
        direccion.put("email", correoDe(userId));
        direccion.put("line1", "Calle Prueba 1");
        direccion.put("city", "Madrid");
        direccion.put("state", "M");
        direccion.put("postalCode", "28001");
        direccion.put("country", PAIS);

        Map<String, Object> linea = new LinkedHashMap<>();
        linea.put("productId", productoId.toString());
        if (varianteId != null) {
            linea.put("variantId", varianteId.toString());
        }
        linea.put("quantity", cantidad);

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("shippingAddressInline", direccion);
        cuerpo.put("items", List.of(linea));
        cuerpo.put("paymentMethod", metodo);

        return client.post().uri(CHECKOUT).header("Authorization", bearer(tokenDe(userId, "USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    // Los endpoints de dinero EXIGEN la clave: identifica el INTENTO. Sin ella el
                    // servidor responde 400 en vez de abrir un segundo cobro. Clave nueva por
                    // llamada —cada petición es un intento distinto—; quien quiere un REENVÍO del
                    // mismo intento pasa la suya y se respeta.
                    {
                        h.set("Idempotency-Key",
                                claveIdem != null ? claveIdem : UUID.randomUUID().toString());
                    }
                })
                .bodyValue(cuerpo).exchange().expectStatus().isCreated().expectBody(MAPA)
                .returnResult().getResponseBody();
    }

    /** Pedido PAGADO con saldo, listo para recorrer el ciclo. Devuelve su id. */
    protected UUID pedidoPagado(UUID userId, UUID productoId, int cantidad) {
        Map<String, Object> creado = checkout(userId, productoId, cantidad, "WALLET", null);
        return UUID.fromString(String.valueOf(creado.get("id")));
    }

    /** Pedido PAGADO comprando una VARIANTE concreta (para observar el inventario). Devuelve su id. */
    protected UUID pedidoPagadoConVariante(UUID userId, UUID productoId, UUID varianteId, int cantidad) {
        Map<String, Object> creado = checkout(userId, productoId, varianteId, cantidad, "WALLET", null);
        return UUID.fromString(String.valueOf(creado.get("id")));
    }

    /** Variante sembrada del producto (solo hay una). */
    protected UUID variantePrincipal(UUID productoId) {
        return jdbcTemplate.queryForObject("SELECT id FROM product_variant WHERE product_id = ?", UUID.class,
                productoId);
    }

    /** Stock declarado de una variante (informativo en dropshipping: no debería moverse nunca). */
    protected int stockDe(UUID varianteId) {
        Integer stock = jdbcTemplate.queryForObject("SELECT stock FROM product_variant WHERE id = ?",
                Integer.class, varianteId);
        return stock == null ? 0 : stock;
    }

    /** Pedido creado pero SIN pagar (pago externo pendiente). Devuelve su id. */
    protected UUID pedidoSinPagar(UUID userId, UUID productoId, int cantidad) {
        Map<String, Object> creado = checkout(userId, productoId, cantidad, "CARD", null);
        return UUID.fromString(String.valueOf(creado.get("id")));
    }

    /** Transición del panel de administración. Devuelve el código HTTP tal cual para poder afirmarlo. */
    protected int transicionAdmin(UUID pedidoId, String accion, String token) {
        return client.post().uri(ADMIN_ORDERS + pedidoId + "/" + accion)
                .header("Authorization", bearer(token)).exchange().returnResult(Void.class)
                .getStatus().value();
    }

    /** Igual que {@link #transicionAdmin} pero enviando un cuerpo (para probar que se ignora). */
    protected int transicionAdminConCuerpo(UUID pedidoId, String accion, String token,
            Map<String, Object> cuerpo) {
        return client.post().uri(ADMIN_ORDERS + pedidoId + "/" + accion)
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo).exchange().returnResult(Void.class).getStatus().value();
    }

    /** Cancelación por el propio cliente. {@code aMonedero} elige el destino del reembolso. */
    protected int cancelarComoCliente(UUID userId, UUID pedidoId, boolean aMonedero) {
        return client.post().uri("/api/me/orders/" + pedidoId + "/cancel?refundToWallet=" + aMonedero)
                .header("Authorization", bearer(tokenDe(userId, "USER"))).exchange()
                .returnResult(Void.class).getStatus().value();
    }

    /** Lleva el pedido hasta el estado pedido usando SOLO los endpoints reales, en orden. */
    protected void avanzarHasta(UUID pedidoId, String estado, String token) {
        List<String> pasos = new ArrayList<>();
        if ("FORWARDED".equals(estado) || "SHIPPED".equals(estado) || "DELIVERED".equals(estado)) {
            pasos.add("forward");
        }
        if ("SHIPPED".equals(estado) || "DELIVERED".equals(estado)) {
            pasos.add("ship");
        }
        if ("DELIVERED".equals(estado)) {
            pasos.add("deliver");
        }
        for (String paso : pasos) {
            int codigo = transicionAdmin(pedidoId, paso, token);
            if (codigo != 200) {
                throw new IllegalStateException("No se pudo avanzar a " + estado + " (" + paso + " → "
                        + codigo + ")");
            }
        }
    }

    /** Resumen de ganancias del operador autenticado, tal y como lo ve él por HTTP. */
    protected Map<String, Object> gananciasDelOperador(String token) {
        return client.get().uri("/api/admin/operator/earnings").header("Authorization", bearer(token))
                .exchange().expectStatus().isOk().expectBody(MAPA).returnResult().getResponseBody();
    }

    /* ==================== Siembra del programa de afiliados ==================== */

    /**
     * Deja al comprador ATRIBUIDO a un afiliado activo: es la condición para que el pedido genere
     * comisión (y para que el comprador se lleve su descuento de referido del 10%).
     *
     * @return los identificadores del afiliado y de su comisión futura, por claves {@code affiliateId} y
     *         {@code affiliateUserId}
     */
    protected Map<String, UUID> sembrarAfiliadoQueRefiere(UUID compradorId) {
        UUID afiliadoUserId = crearUsuario("USER");
        UUID afiliadoId = UUID.randomUUID();
        String codigo = "ref-" + afiliadoId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO affiliate (id, user_id, code, earnings_usd_cents, payout_usd_cents,"
                + " referrals_count, active, status, payout_method, created_at, updated_at)"
                + " VALUES (?, ?, ?, 0, 0, 0, true, 'ACTIVE', 'WALLET', now(), now())",
                afiliadoId, afiliadoUserId, codigo);

        UUID codigoId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO affiliate_referral_code (id, affiliate_id, code, label, active,"
                + " clicks, created_at, updated_at) VALUES (?, ?, ?, 'Primary', true, 1, now(), now())",
                codigoId, afiliadoId, codigo);

        // Atribución viva: el clic apunta ya al comprador y no caduca durante la prueba.
        jdbcTemplate.update("INSERT INTO affiliate_attribution (id, referral_code_id, affiliate_id,"
                + " visitor_token, referred_user_id, clicked_at, expires_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), codigoId, afiliadoId, "visitante-" + compradorId, compradorId,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(30L * 24 * 3600)));

        Map<String, UUID> ids = new HashMap<>();
        ids.put("affiliateId", afiliadoId);
        ids.put("affiliateUserId", afiliadoUserId);
        return ids;
    }

    /** Estado de la comisión del afiliado por un pedido (o null si no se generó ninguna). */
    protected String estadoComisionAfiliado(UUID pedidoId) {
        List<String> filas = jdbcTemplate.queryForList("SELECT c.status FROM affiliate_commission c"
                + " JOIN affiliate_conversion v ON v.id = c.conversion_id WHERE v.order_id = ?",
                String.class, pedidoId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    /** Importe (céntimos) de la comisión del afiliado por un pedido, o -1 si no existe. */
    protected long importeComisionAfiliado(UUID pedidoId) {
        List<Long> filas = jdbcTemplate.queryForList("SELECT c.amount_cents FROM affiliate_commission c"
                + " JOIN affiliate_conversion v ON v.id = c.conversion_id WHERE v.order_id = ?",
                Long.class, pedidoId);
        return filas.isEmpty() ? -1L : filas.get(0);
    }

    /** Estado de la conversión del afiliado por un pedido, o null si no se generó. */
    protected String estadoConversionAfiliado(UUID pedidoId) {
        List<String> filas = jdbcTemplate.queryForList(
                "SELECT status FROM affiliate_conversion WHERE order_id = ?", String.class, pedidoId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    /** Ganancias acumuladas del afiliado (se descuentan al anular la comisión). */
    protected long gananciasAfiliado(UUID afiliadoId) {
        Long v = jdbcTemplate.queryForObject("SELECT earnings_usd_cents FROM affiliate WHERE id = ?",
                Long.class, afiliadoId);
        return v == null ? 0L : v;
    }
}
