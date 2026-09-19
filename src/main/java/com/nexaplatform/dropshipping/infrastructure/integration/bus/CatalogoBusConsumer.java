package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Recibe el catálogo certificado desde el bus y lo aplica en este entorno.
 *
 * <p>Se enciende con {@code nexadrop.bus.consumir}, un interruptor distinto del de publicar: el
 * entorno que consume no publica, o cada producto volvería al bus al guardarse y se realimentaría
 * sin fin.
 *
 * <p>La ficha llega en el mismo formato de la carga masiva, así que se aplica con el importador de
 * siempre: el mismo que espeja las imágenes al almacén propio, crea las variantes y guarda las
 * traducciones. No hay un segundo camino de alta que pueda quedarse atrás.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nexadrop.bus", name = "consumir", havingValue = "true")
// Y además con la conexión encendida: sin ella no hay broker al que hablar, y los
// eventos se acumularían fallando uno tras otro sin que nadie lo mirara.
@ConditionalOnExpression("${nexadrop.bus.enabled:false}")
public class CatalogoBusConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final CatalogUseCase catalogo;
    private final CategoryRepository categoriasJpa;
    private final ProductRepository productos;

    public CatalogoBusConsumer(CatalogUseCase catalogo, CategoryRepository categoriasJpa,
            ProductRepository productos) {
        this.catalogo = catalogo;
        this.categoriasJpa = categoriasJpa;
        this.productos = productos;
    }

    @KafkaListener(topics = EventoBus.CATEGORIA_PUBLICADA, containerFactory = "busListenerContainerFactory")
    public void recibirCategoria(String mensaje) {
        conRastro("categoría", mensaje, () -> aplicarCategoria(mensaje));
    }

    private void aplicarCategoria(String mensaje) {
        CategoriaPublicada evento = leer(mensaje, CategoriaPublicada.class);
        // El padre se busca por su CÓDIGO: el identificador que trae el origen no existe aquí.
        //
        // Con el repositorio de JPA y no con el de dominio: el de dominio construye la categoría
        // entera y, al hacerlo, intenta cargar sus traducciones, que son perezosas. Aquí no hay
        // transacción abierta, así que reventaba con «Cannot lazily initialize collection of role
        // CategoryEntity.translations» y ninguna categoría hija llegaba a crearse. De todo el
        // objeto solo hace falta el identificador.
        UUID padre = evento.padre() == null ? null
                : categoriasJpa.findBySlug(evento.padre()).map(CategoryEntity::getId).orElse(null);
        if (evento.padre() != null && padre == null) {
            // Se lanza para que el mensaje se reintente: la categoría padre puede estar aún en
            // camino. Crearla sin padre la dejaría colgando de la raíz del escaparate, y nadie se
            // daría cuenta hasta ver el árbol torcido.
            throw new IllegalStateException(
                    "Todavía no ha llegado la categoría padre " + evento.padre() + " de " + evento.codigo());
        }
        catalogo.upsertCategory(new IngestCategoryRequest(
                evento.codigo(), padre, "bus", evento.codigo(),
                evento.nombre().get("zh"), 0, null, sinChino(evento.nombre())));
        log.info("Bus -> categoría {} aplicada", evento.codigo());
    }

    @KafkaListener(topics = EventoBus.PRODUCTO_CERTIFICADO, containerFactory = "busListenerContainerFactory")
    public void recibirProducto(String mensaje) {
        conRastro("producto", mensaje, () -> aplicarProducto(mensaje));
    }

    private void aplicarProducto(String mensaje) {
        ProductoCertificado evento = leer(mensaje, ProductoCertificado.class);
        // El importador hace upsert por identificador de origen: reprocesar el mismo mensaje deja
        // el producto igual, no duplicado. Es lo que permite reintentar sin miedo.
        BulkResultDtoOut resultado = catalogo.bulkCreateProducts(List.of(evento.ficha()));
        if (resultado.getFailed() > 0) {
            // Se lanza para que NO se confirme y se reintente: dar por bueno un producto que no ha
            // entrado lo perdería para siempre, sin más rastro que una línea de registro.
            throw new IllegalStateException("No se pudo aplicar el producto "
                    + evento.ficha().getExternalId() + ": " + resultado.getErrors());
        }
        log.info("Bus -> producto {} aplicado", evento.ficha().getExternalId());
    }

    @KafkaListener(topics = EventoBus.PRODUCTO_RETIRADO, containerFactory = "busListenerContainerFactory")
    public void recibirRetirada(String mensaje) {
        conRastro("retirada", mensaje, () -> aplicarRetirada(mensaje));
    }

    private void aplicarRetirada(String mensaje) {
        ProductoRetirado evento = leer(mensaje, ProductoRetirado.class);
        Optional<ProductEntity> producto = productos.findFirstByExternalId(evento.externalId());
        if (producto.isEmpty()) {
            // Retirar algo que aquí nunca llegó a existir no es un error: se da por hecho.
            log.info("Bus -> retirada de {} ignorada: no está en este entorno", evento.externalId());
            return;
        }
        // Se PAUSA, no se borra. Borrarlo se llevaría por delante el historial de los pedidos que
        // ya lo compraron; pausado desaparece del escaparate y el histórico queda intacto.
        catalogo.updateStatus(producto.get().getId(), ProductStatus.PAUSED);
        log.info("Bus -> producto {} retirado del escaparate: {}", evento.externalId(), evento.motivo());
    }

    /** Los nombres traducidos, sin el chino: ese va en su propio campo. */
    private Map<String, String> sinChino(Map<String, String> nombres) {
        return nombres.entrySet().stream()
                .filter(e -> !"zh".equals(e.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private <T> T leer(String mensaje, Class<T> tipo) {
        try {
            return MAPPER.readValue(mensaje, tipo);
        } catch (Exception e) {
            // Un mensaje ilegible no se puede arreglar reintentando: se registra entero para poder
            // repescarlo a mano y se deja pasar, o bloquearía la partición para siempre.
            log.error("Mensaje del bus ilegible, se descarta: {}", mensaje, e);
            throw new IllegalArgumentException("Mensaje del bus ilegible", e);
        }
    }

    /**
     * Ejecuta la aplicación de un mensaje dejando constancia de por qué falla, si falla.
     *
     * <p>Sin esto, un mensaje que no se puede aplicar se reintenta y acaba en el tema de descartes
     * **sin una sola línea en el registro que diga qué pasó**: Spring anota los intentos fallidos en
     * nivel de depuración, que en un entorno real está apagado. Costó una tarde entera de
     * diagnóstico a ciegas —se veía el reintento cada quince segundos, pero no la causa—, así que la
     * causa se registra aquí, con el principio del mensaje para poder identificarlo.
     */
    private void conRastro(String que, String mensaje, Runnable accion) {
        try {
            accion.run();
        } catch (RuntimeException e) {
            log.error("Bus -> NO se pudo aplicar {}: {}: {} · mensaje: {}",
                    que, e.getClass().getSimpleName(), e.getMessage(), abreviar(mensaje), e);
            throw e;
        }
    }

    /** El principio del mensaje, lo justo para reconocerlo sin llenar el registro. */
    private static String abreviar(String mensaje) {
        if (mensaje == null) {
            return "(vacío)";
        }
        return mensaje.length() > 400 ? mensaje.substring(0, 400) + "…" : mensaje;
    }
}
