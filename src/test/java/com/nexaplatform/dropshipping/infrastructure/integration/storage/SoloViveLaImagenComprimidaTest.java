package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ImagenOrigenEspejadaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * De cada foto vive UNA sola copia en el cubo: la comprimida.
 *
 * <p>Regla del titular (25-sep-2026): cuando el {@code .webp} se crea correctamente, el original tiene
 * que desaparecer. Hasta entonces la compresión subía la copia nueva y <b>dejaba la vieja</b>, así que
 * el cubo guardaba las dos: la que sirve el escaparate y una huérfana que ya no apunta nadie —la clave
 * es el hash del contenido, y ese hash cambia al comprimir—. Con 121.825 imágenes son decenas de GB
 * pagados por ficheros que no sirve nadie.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoloViveLaImagenComprimidaTest {

    @Mock
    ProductImageRepository imageRepository;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    VariantValueRepository variantValueRepository;
    @Mock
    ObjectStorageService storage;
    @Mock
    ImagenOrigenEspejadaRepository origenesEspejados;
    @Mock
    CompresorDeImagen compresor;
    @Spy
    LimitadorDeDescargasPorOrigen limitador = new LimitadorDeDescargasPorOrigen(2, 5);

    @InjectMocks
    ImageMirrorService service;

    private static final String ORIGINAL = "https://img.nx036.com/product-images/media/ab/abcdef.jpg";

    private ProductImageEntity imagenSinComprimir() {
        ProductImageEntity img = ProductImageEntity.builder().build();
        img.setId(UUID.randomUUID());
        img.setCdnUrl(ORIGINAL);
        return img;
    }

    /**
     * Un JPEG de mentira pero con peso y con su firma.
     *
     * <p>La firma importa: el servicio olfatea el tipo por los primeros bytes antes de comprimir, y un
     * array de ceros no es ninguna imagen. Sin cabecera, el lote se iba por la rama de error y la
     * prueba «no se borra nada» salía verde por el motivo equivocado.
     */
    private static byte[] bytes(int cuantos) {
        byte[] b = new byte[cuantos];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    @Test
    @DisplayName("al nacer el webp, el original se borra del cubo")
    void alNacerElWebpSeBorraElOriginal() {
        ProductImageEntity img = imagenSinComprimir();
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of(img));
        when(storage.bytesFromPublicUrl(ORIGINAL)).thenReturn(bytes(240_000));
        when(compresor.comprimir(any(), any()))
                .thenReturn(new CompresorDeImagen.Comprimida(bytes(90_000), "webp", "image/webp", 800, 800));
        when(storage.upload(anyString(), any(), anyString()))
                .thenReturn("https://img.nx036.com/product-images/media/cd/cdef.webp");

        int aligeradas = service.comprimirPendientesBatch(10);

        assertThat(aligeradas).isEqualTo(1);
        verify(storage).deleteByPublicUrl(ORIGINAL);
    }

    @Test
    @DisplayName("el original se borra DESPUÉS de apuntar la ficha al webp, nunca antes")
    void seBorraDespuesDeApuntarLaFicha() {
        // El orden es lo que decide si un fallo deja la foto rota o solo un huérfano. Borrando primero,
        // un error al guardar dejaría la ficha apuntando a un objeto inexistente y la imagen
        // desaparecería del escaparate sin dar ningún error: exactamente el fallo que se acaba de
        // arreglar en preproducción, con 8.653 referencias muertas.
        ProductImageEntity img = imagenSinComprimir();
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of(img));
        when(storage.bytesFromPublicUrl(ORIGINAL)).thenReturn(bytes(240_000));
        when(compresor.comprimir(any(), any()))
                .thenReturn(new CompresorDeImagen.Comprimida(bytes(90_000), "webp", "image/webp", 800, 800));
        when(storage.upload(anyString(), any(), anyString()))
                .thenReturn("https://img.nx036.com/product-images/media/cd/cdef.webp");

        service.comprimirPendientesBatch(10);

        // UN solo InOrder, y sin `never()` dentro: dos instancias distintas no comparan entre sí, y un
        // `never()` en orden no dice «no antes», dice «nunca» — la primera versión de esta prueba fallaba
        // por eso, no por el código.
        InOrder orden = inOrder(imageRepository, storage);
        orden.verify(imageRepository).reapuntaLasQueCompartianElOriginal(eq(ORIGINAL), anyString(), anyLong(),
                anyString(), any(Instant.class));
        orden.verify(storage).deleteByPublicUrl(ORIGINAL);
    }

    @Test
    @DisplayName("si comprimir no aligera, no se borra nada: la original sigue siendo la buena")
    void siNoAligeraNoSeBorraNada() {
        // EL control. La foto ya optimizada en origen se queda como está y sigue siendo la única copia:
        // borrarla dejaría el producto sin imagen.
        ProductImageEntity img = imagenSinComprimir();
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of(img));
        when(storage.bytesFromPublicUrl(ORIGINAL)).thenReturn(bytes(90_000));
        when(compresor.comprimir(any(), any()))
                .thenReturn(new CompresorDeImagen.Comprimida(bytes(95_000), "webp", "image/webp", 800, 800));

        assertThat(service.comprimirPendientesBatch(10)).isZero();

        verify(storage, never()).deleteByPublicUrl(any());
        verify(storage, never()).upload(any(), any(), any());
    }

    /**
     * EL fallo que rompió preproducción el 25-sep-2026, y el que esta clase no cubría.
     *
     * <p>El objeto se nombra por el hash de su CONTENIDO, así que la misma foto usada por dos productos
     * —o en la galería y en una variante— es UN objeto y VARIAS filas. Medido: 891 URLs compartidas por
     * 1.896 filas. Al comprimir se actualizaba SOLO la fila del lote y se borraba el original, dejando a
     * las hermanas apuntando a un objeto inexistente: la foto desaparecía del escaparate sin dar un solo
     * error y la fila seguía diciendo MIRRORED.
     *
     * <p>Lo que se vio: fichas con el hueco gris en el escaparate, con la base de datos afirmando que
     * estaban espejadas y el objeto ausente del cubo. Un 404 que además el borde cachea un año.
     */
    @Test
    @DisplayName("comprimir reapunta TODAS las filas que compartían el original, no solo la del lote")
    void reapuntaALasHermanasQueCompartianElOriginal() {
        ProductImageEntity img = imagenSinComprimir();
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of(img));
        when(storage.bytesFromPublicUrl(ORIGINAL)).thenReturn(bytes(240_000));
        when(compresor.comprimir(any(), any()))
                .thenReturn(new CompresorDeImagen.Comprimida(bytes(90_000), "webp", "image/webp", 800, 800));
        String comprimida = "https://img.nx036.com/product-images/media/cd/cdef.webp";
        when(storage.upload(anyString(), any(), anyString())).thenReturn(comprimida);

        service.comprimirPendientesBatch(10);

        // Se reapunta por la URL DEL ORIGINAL, que es lo que alcanza a las hermanas. Hacerlo por el id de
        // la fila —como antes— es justo lo que las dejaba huérfanas.
        verify(imageRepository).reapuntaLasQueCompartianElOriginal(eq(ORIGINAL), eq(comprimida), anyLong(), anyString(),
                any(Instant.class));
        verify(imageRepository, never()).marcaComprimida(any(), anyString(), anyLong(), anyString(),
                any(Instant.class));
    }

    /**
     * La MEMORIA de descargas también se reapunta, o reencolar deja de arreglar nada.
     *
     * <p>{@code imagen_origen_espejada} guarda {@code url_de_origen → cdn_url} para no volver a bajar lo
     * que ya se bajó. Si la compresión borra el original y no la toca, esa memoria queda apuntando a un
     * objeto muerto, y entonces el espejador consulta la tabla, encuentra una URL y da la foto por
     * espejada <b>sin descargar ni subir nada</b>: la fila vuelve a MIRRORED apuntando al mismo objeto
     * borrado.
     *
     * <p>Se vio en preproducción el 25-sep-2026: el auto-sanado reencoló 806 imágenes, la cola se vació
     * en segundos y las 806 seguían rotas. 8.083 entradas de la memoria estaban envenenadas así.
     */
    @Test
    @DisplayName("comprimir reapunta también la memoria de descargas, no solo las fichas")
    void reapuntaLaMemoriaDeDescargas() {
        ProductImageEntity img = imagenSinComprimir();
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of(img));
        when(storage.bytesFromPublicUrl(ORIGINAL)).thenReturn(bytes(240_000));
        when(compresor.comprimir(any(), any()))
                .thenReturn(new CompresorDeImagen.Comprimida(bytes(90_000), "webp", "image/webp", 800, 800));
        String comprimida = "https://img.nx036.com/product-images/media/cd/cdef.webp";
        when(storage.upload(anyString(), any(), anyString())).thenReturn(comprimida);

        service.comprimirPendientesBatch(10);

        // Y ANTES de borrar: si se borra primero y falla la escritura, la memoria sigue mandando a todo
        // el mundo a un objeto que ya no está.
        InOrder orden = inOrder(origenesEspejados, storage);
        orden.verify(origenesEspejados).reapunta(ORIGINAL, comprimida);
        orden.verify(storage).deleteByPublicUrl(ORIGINAL);
    }

    @Test
    @DisplayName("si la comprimida cae en la misma clave, no se borra: sería borrar la buena")
    void mismaClaveNoSeBorra() {
        // Borde real: si el contenido no cambia, el hash tampoco, y la URL nueva es la misma que la
        // vieja. Borrarla dejaría la ficha apuntando a un objeto que se acaba de eliminar.
        ProductImageEntity img = imagenSinComprimir();
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of(img));
        when(storage.bytesFromPublicUrl(ORIGINAL)).thenReturn(bytes(240_000));
        when(compresor.comprimir(any(), any()))
                .thenReturn(new CompresorDeImagen.Comprimida(bytes(90_000), "webp", "image/webp", 800, 800));
        when(storage.upload(anyString(), any(), anyString())).thenReturn(ORIGINAL);

        service.comprimirPendientesBatch(10);

        verify(storage, never()).deleteByPublicUrl(any());
    }
}
