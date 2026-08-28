package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.EventPublisher;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.OutboxDispatcher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Traduce lo que hay en la base a los eventos del bus, y los publica.
 *
 * <p>Aquí vive la decisión de QUÉ sale de este entorno. Dos reglas que no se
 * pueden relajar:
 *
 * <ol>
 *   <li><b>Las imágenes van con la URL de ORIGEN.</b> El destino ya espeja las
 *       fotos a su propio almacén; mandarle la de este entorno lo dejaría
 *       sirviendo imágenes de aquí, y apagar preproducción dejaría la tienda sin
 *       fotos.</li>
 *   <li><b>La categoría viaja por su CÓDIGO, no por su identificador.</b> Los
 *       identificadores son propios de cada base: con el de aquí, el producto
 *       llegaría colgando de una categoría que allí no existe.</li>
 * </ol>
 *
 * <p><b>Publicar y consumir son interruptores distintos a propósito.</b> Este servicio se enciende
 * con {@code nexadrop.bus.publicar} y el consumidor con {@code nexadrop.bus.consumir}. Si el
 * entorno que consume publicara además lo que recibe, cada producto volvería al bus al guardarse y
 * se realimentaría sin fin. Con dos interruptores, el bucle es imposible de montar por descuido.
 *
 * <p>No se publica contra el broker desde aquí: el evento se deja en la bandeja de
 * salida (outbox), en la MISMA transacción que guarda el producto. Publicar dentro
 * de la transacción anunciaría productos que un rollback posterior deja sin
 * existir; publicar después, sin bandeja, los perdería si el bus está caído justo
 * en ese instante. El {@link OutboxDispatcher} los envía luego, y reintenta.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "nexadrop.bus", name = "publicar", havingValue = "true")
// Y además con la conexión encendida: sin ella no hay broker al que hablar, y los
// eventos se acumularían fallando uno tras otro sin que nadie lo mirara.
@ConditionalOnExpression("${nexadrop.bus.enabled:false}")
public class CatalogoBusService {

    private static final String AGREGADO = "producto";
    /** Marca el evento para que el dispatcher lo mande al bus y no al Kafka interno. */
    private static final Map<String, String> AL_BUS = Map.of(OutboxDispatcher.CABECERA_DESTINO,
            OutboxDispatcher.DESTINO_BUS);

    private final EventPublisher publicador;

    public CatalogoBusService(EventPublisher publicador) {
        this.publicador = publicador;
    }

    /**
     * Encola un producto recién certificado.
     *
     * <p>La ficha llega ya exportada por quien llama. Se hace así, y no pidiéndola aquí, para no
     * crear un ciclo: el caso de uso del catálogo depende de este servicio, y si este servicio
     * dependiera del caso de uso, la aplicación no arrancaría.
     */
    public void publicarCertificado(BulkProductDtoIn ficha) {
        // La clave de partición es el identificador de ORIGEN, no el de la base: es lo único que
        // significa lo mismo en los dos entornos, y garantiza que todos los cambios de un mismo
        // producto se procesen en orden.
        publicador.publish(EventoBus.PRODUCTO_CERTIFICADO, AGREGADO, ficha.getExternalId(),
                ficha.getExternalId(), ProductoCertificado.de(ficha), AL_BUS);
        log.info("Bus <- producto certificado {} ({})", ficha.getExternalId(), ficha.getTitleEs());
    }

    /** Encola la retirada de un producto que deja de estar certificado. */
    public void publicarRetirado(ProductEntity p, String motivo) {
        publicador.publish(EventoBus.PRODUCTO_RETIRADO, AGREGADO, p.getExternalId(),
                p.getExternalId(), ProductoRetirado.de(p.getExternalId(), p.getSlug(), motivo), AL_BUS);
        log.info("Bus <- producto retirado {} ({}): {}", p.getExternalId(), p.getSlug(), motivo);
    }

    /**
     * Encola una categoría. Se publican SIEMPRE, estén o no certificados sus productos: la categoría
     * tiene que existir en el destino antes de que llegue el primer producto que cuelga de ella, o
     * el producto llega huérfano y no aparece en ninguna parte del escaparate.
     */
    public void publicarCategoria(CategoryEntity c) {
        Map<String, String> nombre = new HashMap<>();
        if (c.getTranslations() != null) {
            c.getTranslations().forEach(t -> {
                if (t.getName() != null) {
                    nombre.put(t.getLanguage(), t.getName());
                }
            });
        }
        if (c.getNameZh() != null) {
            nombre.put("zh", c.getNameZh());
        }
        // El padre viaja por su CÓDIGO, por el mismo motivo que la categoría del producto: el
        // identificador de esta base no significa nada en la de destino.
        String padre = c.getParent() != null ? c.getParent().getSlug() : null;
        publicador.publish(EventoBus.CATEGORIA_PUBLICADA, "categoria", c.getSlug(), c.getSlug(),
                CategoriaPublicada.de(c.getSlug(), nombre, padre, c.isActive()), AL_BUS);
        log.info("Bus <- categoría {}", c.getSlug());
    }
}
