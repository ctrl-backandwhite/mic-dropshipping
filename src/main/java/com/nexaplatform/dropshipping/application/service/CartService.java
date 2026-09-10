package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CartItemDto;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CartItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CartItemJpaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carrito ACTIVO ligado al usuario (no al dispositivo): lo que se añade en la web aparece en la app y al
 * revés. Reglas:
 * <ul>
 *   <li><b>Identidad de la línea = (producto, variante).</b> Dos variantes del mismo producto son dos
 *       líneas distintas, con su cantidad propia.</li>
 *   <li><b>{@link #upsert} FIJA la cantidad</b> (no la suma). Es la operación de la pantalla del carrito,
 *       donde la persona elige un número: si sumara, no habría forma de bajar de 3 a 2 unidades, y un
 *       reintento por red inestable —habitual en móvil— duplicaría lo pedido. Quien "añade" ya conoce la
 *       cesta y manda el total resultante.</li>
 *   <li><b>{@link #merge} SUMA</b>: es lo que espera quien llenó la cesta sin sesión y luego entra. No
 *       puede perder ni lo que traía del navegador ni lo que ya tenía en la cuenta.</li>
 *   <li><b>La cantidad nunca baja del MOQ</b> del producto (ni de 1). El mínimo se lee del CATÁLOGO, no
 *       del snapshot que manda el cliente: ese campo es solo de pintado y es manipulable.</li>
 *   <li><b>El precio no se recalcula aquí.</b> El snapshot sirve para pintar la línea; el importe que se
 *       cobra lo resuelve el presupuesto ({@code /api/catalog/cart-quote}) con la tarifa y la tasa del
 *       día.</li>
 *   <li>Solo se guardan productos que existen; una referencia inexistente lanza {@link NotFoundException}
 *       (así una cesta vieja no arrastra productos retirados del catálogo).</li>
 * </ul>
 *
 * <p><b>Aislamiento.</b> El {@code userId} llega desde la autenticación y entra en el filtro de TODAS las
 * consultas. Ningún método acepta un identificador de usuario del cuerpo o de la URL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    /** Tope duro de unidades por línea, coherente con la validación del checkout. */
    private static final int MAX_QTY = 100_000;

    /** El papel que marca la foto principal en la galería. Conviven mayúsculas y minúsculas. */
    private static final String PAPEL_PRINCIPAL = "MAIN";

    /** El papel del vídeo, que NO sirve como foto de la línea. */
    private static final String PAPEL_VIDEO = "VIDEO";

    private final CartItemJpaRepository repo;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;

    @Transactional(readOnly = true)
    public List<CartItemDto> list(UUID userId) {
        return conLaFotoQueFalte(repo.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream()
                .map(CartItemDto::fromEntity).toList());
    }

    /**
     * Rellena la foto de las líneas que se guardaron sin ella.
     *
     * <p>La línea guarda una FOTO FIJA de lo que se veía al añadirla —título, precio, imagen—, y eso es
     * deliberado. El problema es que una foto fija hereda para siempre los fallos del día en que se tomó:
     * hasta el 9-sep-2026 la web mandaba la imagen vacía al añadir desde una ficha con variantes, y esas
     * líneas se quedaron con un hueco gris que no se arregla solo. Arreglar el cliente no las recupera:
     * ya están guardadas así, y para quien las tiene en su cesta el defecto sigue ahí cada vez que entra.
     *
     * <p>Así que se resuelve al SERVIR, que es lo único que alcanza a lo ya guardado. No se escribe nada
     * en la base: si mañana el producto cambia de foto, la cesta enseña la buena sin arrastrar la vieja.
     *
     * <p>Solo se pregunta por los productos a los que les falta, y en UNA consulta para todos. Una cesta
     * de quince líneas no puede costar quince viajes a la base de datos para pintar una pantalla.
     */
    private List<CartItemDto> conLaFotoQueFalte(List<CartItemDto> lineas) {
        List<UUID> sinFoto = lineas.stream().filter(l -> enBlanco(l.image())).map(CartItemDto::productId).distinct()
                .toList();
        if (sinFoto.isEmpty()) {
            return lineas;
        }

        Map<UUID, String> fotos = fotosDe(sinFoto);
        List<CartItemDto> salida = new ArrayList<>(lineas.size());
        for (CartItemDto linea : lineas) {
            String foto = enBlanco(linea.image()) ? fotos.get(linea.productId()) : linea.image();
            salida.add(enBlanco(foto) ? linea : linea.conImagen(foto));
        }
        return salida;
    }

    /** La foto que representa a cada producto: la marcada como principal y, si no hay, la primera. */
    private Map<UUID, String> fotosDe(List<UUID> productIds) {
        Map<UUID, ProductImageEntity> elegidas = new HashMap<>();
        for (ProductImageEntity imagen : productImageRepository.findByProductIdInOrderByPositionAsc(productIds)) {
            if (esVideo(imagen) || enBlanco(direccionDe(imagen))) {
                continue;
            }
            UUID producto = imagen.getProduct().getId();
            ProductImageEntity actual = elegidas.get(producto);
            if (actual == null || (esPrincipal(imagen) && !esPrincipal(actual))) {
                elegidas.put(producto, imagen);
            }
        }

        Map<UUID, String> fotos = new HashMap<>();
        elegidas.forEach((producto, imagen) -> fotos.put(producto, direccionDe(imagen)));
        return fotos;
    }

    /**
     * La copia espejada, y solo si falta, la del proveedor.
     *
     * <p>El orden importa: la del proveedor apunta a un servidor ajeno que bloquea las peticiones desde
     * otros dominios, así que servirla es enseñar una imagen rota con otro nombre.
     */
    private static String direccionDe(ProductImageEntity imagen) {
        return enBlanco(imagen.getCdnUrl()) ? imagen.getSourceUrl() : imagen.getCdnUrl();
    }

    private static boolean esPrincipal(ProductImageEntity imagen) {
        return imagen.getRole() != null && PAPEL_PRINCIPAL.equalsIgnoreCase(imagen.getRole());
    }

    private static boolean esVideo(ProductImageEntity imagen) {
        return imagen.getRole() != null && PAPEL_VIDEO.equalsIgnoreCase(imagen.getRole());
    }

    private static boolean enBlanco(String texto) {
        return texto == null || texto.isBlank();
    }

    /** Añade o actualiza una línea FIJANDO su cantidad; devuelve la cesta completa. */
    @Transactional
    public List<CartItemDto> upsert(UUID userId, CartItemDto dto) {
        save(userId, dto, false);
        return list(userId);
    }

    /** Funde una lista con la que ya haya, SUMANDO las cantidades de las líneas coincidentes. */
    @Transactional
    public List<CartItemDto> merge(UUID userId, List<CartItemDto> items) {
        if (items != null) {
            for (CartItemDto dto : items) {
                if (dto != null) {
                    save(userId, dto, true);
                }
            }
        }
        return list(userId);
    }

    @Transactional
    public List<CartItemDto> remove(UUID userId, UUID productId, UUID variantId) {
        repo.deleteByUserIdAndProductIdAndVariantId(userId, productId, variantId);
        return list(userId);
    }

    /** Vacía la cesta del usuario — al completar un pedido, o cuando la persona la descarta entera. */
    @Transactional
    public List<CartItemDto> clear(UUID userId) {
        repo.deleteByUserId(userId);
        return List.of();
    }

    /**
     * Saca de la cesta las líneas de un pedido que acaba de quedar PAGADO.
     *
     * <p><b>Solo al pasar a PAGADO, nunca al crear el pedido.</b> Con tarjeta, PayPal o USDT el pedido
     * nace pendiente y el dinero puede no llegar jamás: vaciar ahí dejaría a la persona sin pedido y sin
     * cesta. Con monedero el cobro es inmediato, así que los dos casos quedan cubiertos con el mismo
     * disparador.
     *
     * <p><b>Se borra lo comprado, no la cesta entera.</b> Quien tramita solo una parte conserva el resto;
     * como el checkout normalmente lleva todas las líneas, el resultado habitual es una cesta vacía.
     *
     * <p><b>El usuario sale del PEDIDO</b>, no de un parámetro de fuera: igual que el resto de la clase,
     * no hay forma de tocar la cesta de otra persona. Un pedido sin dueño (partner/API) o sin líneas no
     * borra nada, y quien compró desde un cliente antiguo —sin cesta en el servidor— simplemente no tiene
     * filas que quitar.
     *
     * <p><b>Idempotente:</b> borrar una línea que ya no está es un no-op, así que una segunda confirmación
     * del mismo cobro (un webhook repetido) no falla. Aun así, quien llama solo debe invocarlo en la
     * transición real a PAGADO, para no borrar lo que la persona haya vuelto a añadir después.
     *
     * <p>Corre en su PROPIA transacción ({@code REQUIRES_NEW}) a propósito: si el borrado falla, el error
     * no puede marcar como "rollback-only" la transacción del cobro y tumbar un pedido ya pagado. Los
     * llamadores, además, registran el fallo y siguen.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void removePurchased(Order order) {
        if (order == null || order.getUserId() == null || order.getItems() == null) {
            return;
        }
        UUID userId = order.getUserId();
        for (OrderItem item : order.getItems()) {
            if (item != null && item.getProductId() != null) {
                repo.deleteByUserIdAndProductIdAndVariantId(userId, item.getProductId(), item.getVariantId());
            }
        }
    }

    /**
     * Persiste una línea. Con {@code sumar} a false la cantidad recibida SUSTITUYE a la guardada (pantalla
     * del carrito); con true se acumula sobre ella (fusión al iniciar sesión).
     */
    private void save(UUID userId, CartItemDto dto, boolean sumar) {
        ProductEntity producto = productRepository.findById(dto.productId())
                .orElseThrow(() -> new NotFoundException("Product"));
        // El mínimo manda sobre lo pedido: por debajo del MOQ el proveedor no sirve el pedido.
        int minimo = Math.max(1, producto.getMoq());
        CartItemEntity linea = repo
                .findByUserIdAndProductIdAndVariantId(userId, dto.productId(), dto.variantId())
                .orElseGet(() -> CartItemEntity.builder()
                        .userId(userId).productId(dto.productId()).variantId(dto.variantId()).quantity(0).build());
        int pedida = Math.max(1, dto.quantity());
        // La suma se hace en long a propósito: el merge NO valida el cuerpo campo a campo y una cantidad
        // enorme desbordaría el int, dando una cantidad negativa (el desbordamiento de cantidad ya provocó
        // un cobro incorrecto en el checkout). Con long, el tope de abajo recorta antes de volver a int.
        long deseada = sumar ? (long) linea.getQuantity() + pedida : pedida;
        linea.setQuantity((int) Math.min(MAX_QTY, Math.max(minimo, deseada)));
        applySnapshot(linea, dto, minimo);
        repo.save(linea);
    }

    /**
     * Refresca los datos "de pintado" (precio/título/imagen/variante) al valor más reciente recibido. El
     * MOQ es la excepción: se devuelve el del CATÁLOGO, no el que mandó el cliente, para que el selector
     * de unidades de la web y el de la app enseñen el mismo mínimo real aunque uno tenga la ficha vieja.
     */
    private void applySnapshot(CartItemEntity entity, CartItemDto dto, int moqCatalogo) {
        entity.setSku(dto.sku());
        entity.setSlug(dto.slug());
        entity.setTitle(dto.title());
        // La imagen solo se pisa si la nueva TRAE algo. Una línea guardada con su foto no puede perderla
        // porque un cliente la mande vacía —una pestaña con la versión vieja de la web, o la app— y el
        // borrado sería permanente: la siguiente lectura ya no tendría de dónde sacarla.
        if (!enBlanco(dto.image())) {
            entity.setImageUrl(dto.image());
        }
        entity.setVariantLabel(dto.variantLabel());
        entity.setUnitPriceSource(dto.unitPriceSource());
        entity.setSourceCurrency(dto.sourceCurrency());
        entity.setMoq(moqCatalogo);
        entity.setUnitPriceDisplay(dto.unitPriceDisplay());
        entity.setDisplayCurrency(dto.displayCurrency());
        entity.setDisplaySymbol(dto.displaySymbol());
    }
}
