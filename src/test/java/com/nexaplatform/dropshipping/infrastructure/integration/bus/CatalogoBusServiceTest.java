package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.EventPublisher;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.OutboxDispatcher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Lo que sale de este entorno hacia el bus.
 *
 * <p>Nada se manda directo al broker: todo pasa por la bandeja de salida, dentro de la misma
 * transacción que guarda el producto. Es lo que impide anunciar un producto que un fallo posterior
 * deja sin existir, y lo que evita perderlo si el bus está caído justo en ese momento.
 */
@DisplayName("Publicación del catálogo al bus de integración")
class CatalogoBusServiceTest {

    private EventPublisher publicador;
    private CatalogoBusService servicio;

    @BeforeEach
    void setUp() {
        publicador = mock(EventPublisher.class);
        servicio = new CatalogoBusService(publicador);
    }

    @Test
    @DisplayName("El evento se marca para el bus, no para el Kafka interno de la tienda")
    void seMarcaParaElBus() {
        // Sin esta cabecera el catálogo saldría por el broker de la tienda, donde no lo espera nadie.
        servicio.publicarCertificado(ficha());

        assertThat(cabeceras(EventoBus.PRODUCTO_CERTIFICADO))
                .containsEntry(OutboxDispatcher.CABECERA_DESTINO, OutboxDispatcher.DESTINO_BUS);
    }

    @Test
    @DisplayName("La clave de partición es el identificador de ORIGEN, el único común a los dos entornos")
    void particionaPorIdentificadorDeOrigen() {
        // Con el identificador de la base local, los cambios de un mismo producto podrían repartirse
        // entre particiones distintas y procesarse desordenados: una corrección llegaría antes que
        // el alta que la provoca.
        servicio.publicarCertificado(ficha());

        verify(publicador).publish(anyString(), anyString(), eq("1688-987"), eq("1688-987"), any(), any());
    }

    @Test
    @DisplayName("Viaja la ficha completa, en el mismo formato que consume la carga masiva")
    void viajaLaFichaCompleta() {
        // Reutilizar ese formato es lo que garantiza que un campo nuevo del producto llegue al
        // destino sin que nadie tenga que acordarse de añadirlo también aquí.
        servicio.publicarCertificado(ficha());

        ProductoCertificado evento = (ProductoCertificado) carga(EventoBus.PRODUCTO_CERTIFICADO);
        assertThat(evento.ficha().getExternalId()).isEqualTo("1688-987");
        assertThat(evento.ficha().getTitleEs()).isEqualTo("Abrigo de lana");
        assertThat(evento.ficha().getCategorySlug()).isEqualTo("moda-mujer-abrigos");
        assertThat(evento.version()).isEqualTo(EventoBus.VERSION);
    }

    @Test
    @DisplayName("Descertificar publica la retirada con su motivo")
    void publicarRetirado() {
        ProductEntity p = ProductEntity.builder().externalId("1688-987").slug("abrigo-de-lana").build();

        servicio.publicarRetirado(p, "producto prohibido");

        ProductoRetirado evento = (ProductoRetirado) carga(EventoBus.PRODUCTO_RETIRADO);
        assertThat(evento.motivo()).isEqualTo("producto prohibido");
        assertThat(evento.externalId()).isEqualTo("1688-987");
    }

    @Test
    @DisplayName("La categoría viaja por su CÓDIGO y con el padre por código también")
    void laCategoriaViajaPorCodigo() {
        // Los identificadores son propios de cada base: con el de aquí, la rama entera colgaría de
        // una categoría que en el destino no existe.
        servicio.publicarCategoria(categoria());

        CategoriaPublicada evento = (CategoriaPublicada) carga(EventoBus.CATEGORIA_PUBLICADA);
        assertThat(evento.codigo()).isEqualTo("moda-mujer-abrigos");
        assertThat(evento.padre()).isEqualTo("moda-mujer");
        assertThat(evento.activa()).isTrue();
    }

    @Test
    @DisplayName("La categoría lleva sus nombres traducidos y el chino como fuente")
    void laCategoriaLlevaSusIdiomas() {
        servicio.publicarCategoria(categoria());

        CategoriaPublicada evento = (CategoriaPublicada) carga(EventoBus.CATEGORIA_PUBLICADA);
        assertThat(evento.nombre())
                .containsEntry("es", "Abrigos")
                .containsEntry("en", "Coats")
                .containsEntry("zh", "大衣");
    }

    @Test
    @DisplayName("Las categorías también salen marcadas para el bus")
    void laCategoriaVaAlBus() {
        servicio.publicarCategoria(categoria());

        assertThat(cabeceras(EventoBus.CATEGORIA_PUBLICADA))
                .containsEntry(OutboxDispatcher.CABECERA_DESTINO, OutboxDispatcher.DESTINO_BUS);
    }

    @Test
    @DisplayName("El evento se puede convertir a JSON con un serializador PELADO, sin módulos extra")
    void elEventoSeSerializaSinModulosExtra() {
        // Esto no es un capricho: la bandeja de salida convierte el evento a JSON con el serializador
        // de la aplicación, y ese no sabe escribir los tipos de fecha de Java sin un módulo aparte.
        // Cuando el evento llevaba un Instant, la conversión reventaba DENTRO de la transacción que
        // guardaba el producto, así que marcar uno como verificado no llegaba a guardarse y en la
        // pantalla seguía saliendo "Verificado: No", sin ningún error a la vista.
        ObjectMapper pelado = new ObjectMapper();

        assertThatCode(() -> {
            pelado.convertValue(ProductoCertificado.de(ficha()), Map.class);
            pelado.convertValue(ProductoRetirado.de("1688-987", "abrigo", "motivo"), Map.class);
            pelado.convertValue(CategoriaPublicada.de("moda", Map.of("es", "Moda"), null, true), Map.class);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("La marca de tiempo viaja como texto ISO, legible por cualquier consumidor")
    void laMarcaDeTiempoEsTextoIso() {
        // Del otro lado del bus puede haber servicios que no son Java.
        String ocurrido = ProductoCertificado.de(ficha()).ocurrido();

        assertThat(ocurrido).isNotBlank();
        assertThatCode(() -> Instant.parse(ocurrido)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("La rama de categorías viaja entera y de la RAÍZ hacia abajo")
    void laRamaViajaDeLaRaizHaciaAbajo() {
        // Un entorno que se estrena tiene el árbol vacío: solo ve las categorías cuando alguien las
        // edita, y eso no ha pasado nunca allí. Sin esto, el primer producto certificado llegaba con
        // el código de una categoría inexistente, el importador lo rechazaba y el mensaje se
        // reintentaba sin fin. Pasó de verdad: producción tenía CERO categorías frente a las 1.963
        // de preproducción.
        //
        // Y de la raíz hacia abajo porque cada una necesita que su madre exista antes.
        CategoryEntity raiz = CategoryEntity.builder().slug("moda").active(true).build();
        CategoryEntity media = CategoryEntity.builder().slug("moda-calzado").parent(raiz).active(true).build();
        CategoryEntity hoja = CategoryEntity.builder().slug("moda-cal-30").parent(media).active(true).build();

        servicio.publicarCategoriaConAncestros(hoja);

        ArgumentCaptor<String> codigos = ArgumentCaptor.captor();
        verify(publicador, times(3)).publish(eq(EventoBus.CATEGORIA_PUBLICADA), anyString(),
                codigos.capture(), anyString(), any(), any());
        assertThat(codigos.getAllValues()).containsExactly("moda", "moda-calzado", "moda-cal-30");
    }

    @Test
    @DisplayName("Una categoría que cuelga de sí misma no deja el proceso dando vueltas")
    void unCicloNoCuelgaElProceso() {
        // No debería ocurrir, pero un árbol con un ciclo colgaría la petición del administrador.
        CategoryEntity a = CategoryEntity.builder().slug("bucle").active(true).build();
        a.setParent(a);

        servicio.publicarCategoriaConAncestros(a);

        verify(publicador, times(1)).publish(eq(EventoBus.CATEGORIA_PUBLICADA), anyString(),
                anyString(), anyString(), any(), any());
    }

    /** La carga útil que se ha encolado para el tema indicado. */
    private Object carga(String tema) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.captor();
        verify(publicador).publish(eq(tema), anyString(), anyString(), anyString(), captor.capture(), any());
        return captor.getValue();
    }

    /** Las cabeceras con las que se ha encolado el evento del tema indicado. */
    private Map<String, String> cabeceras(String tema) {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.captor();
        verify(publicador).publish(eq(tema), anyString(), anyString(), anyString(), any(), captor.capture());
        return captor.getValue();
    }

    /** Ficha tal como la deja el exportador del catálogo. */
    private BulkProductDtoIn ficha() {
        BulkProductDtoIn d = new BulkProductDtoIn();
        d.setExternalId("1688-987");
        d.setTitleEs("Abrigo de lana");
        d.setTitleZh("羊毛大衣");
        d.setCategorySlug("moda-mujer-abrigos");
        d.setImageUrls(List.of("https://cbu01.alicdn.com/img/ibank/foto.jpg"));
        return d;
    }

    /** Categoría hoja con su padre y sus traducciones. */
    private CategoryEntity categoria() {
        CategoryEntity padre = CategoryEntity.builder().slug("moda-mujer").active(true).build();
        CategoryEntity c = CategoryEntity.builder()
                .slug("moda-mujer-abrigos").nameZh("大衣").active(true).parent(padre).build();
        c.setTranslations(List.of(
                CategoryTranslationEntity.builder().category(c).language("es").name("Abrigos").build(),
                CategoryTranslationEntity.builder().category(c).language("en").name("Coats").build()));
        return c;
    }
}
