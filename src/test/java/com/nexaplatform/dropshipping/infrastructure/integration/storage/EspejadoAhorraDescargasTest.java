package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ImagenOrigenEspejadaEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ImagenOrigenEspejadaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que impide que el catálogo tarde meses en espejarse: no bajar dos veces la misma URL, no pedirle
 * al proveedor más de lo que tolera, y no comprimir en el camino crítico.
 *
 * <p>Qué se rompería en producción si estas pruebas fallaran: con 100.000 productos son unos 5,2
 * millones de descargas a ~4.200/hora, es decir 52 días con las fichas enseñando el hueco. El
 * proveedor responde 403 a quien enlaza sus imágenes desde otra web, así que una imagen sin espejar
 * es una imagen rota para el comprador.
 */
@ExtendWith(MockitoExtension.class)
class EspejadoAhorraDescargasTest {

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
    @Spy
    LimitadorDeDescargasPorOrigen limitador = new LimitadorDeDescargasPorOrigen(2, 5);
    @InjectMocks
    ImageMirrorService service;

    private static final String URL = "https://cbu01.alicdn.com/img/ibank/2026/foto_!!123-0-cib.jpg";

    @Test
    void unaUrlYaDescargadaSeReaprovechaSinVolverATocarLaRed() {
        UUID id = UUID.randomUUID();
        when(origenesEspejados.findById(ImageMirrorService.hashDeUrl(URL)))
                .thenReturn(Optional.of(ImagenOrigenEspejadaEntity.builder()
                        .urlHash(ImageMirrorService.hashDeUrl(URL))
                        .urlOrigen(URL)
                        .cdnUrl("https://img.nx036.com/product-images/media/ab/abcd.webp")
                        .bytes(1234L).hash("abcd").ancho(800).alto(600)
                        .comprimida(true).creadaEn(Instant.now()).usadaEn(Instant.now()).veces(1)
                        .build()));

        boolean espejada = service.mirrorOne(id, URL);

        assertThat(espejada).isTrue();
        // La ficha queda apuntando a lo que ya teníamos, sin pasar por el proveedor.
        verify(imageRepository).markMirrored(eq(id),
                eq("https://img.nx036.com/product-images/media/ab/abcd.webp"), eq(1234L), eq("abcd"),
                eq(800), eq(600), eq(MirrorStatus.MIRRORED), any(Instant.class));
        // Y se anota el reaprovechamiento: es la única forma de saber si esta memoria sirve de algo.
        verify(origenesEspejados).anotaUso(eq(ImageMirrorService.hashDeUrl(URL)), any(Instant.class));
        // Venía comprimida, así que la segunda pasada no tiene nada que hacer con ella.
        verify(imageRepository).marcaSinComprimir(eq(id), any(Instant.class));
        // Nada tocó el almacenamiento para subir: no hubo descarga.
        verify(storage, never()).upload(any(), any(), any());
    }

    @Test
    void unaUrlDesconocidaNoSeDaPorEspejadaSinDescargarla() {
        UUID id = UUID.randomUUID();
        when(origenesEspejados.findById(any())).thenReturn(Optional.empty());

        // Sin red, la descarga falla y la imagen se marca como fallida. Lo que importa aquí es que NO
        // se inventa un espejado: una URL desconocida tiene que intentar bajarse de verdad.
        boolean espejada = service.mirrorOne(id, "https://ejemplo.invalido/no-existe.jpg");

        assertThat(espejada).isFalse();
        verify(imageRepository).markFailed(id);
        verify(imageRepository, never()).markMirrored(any(), any(), anyLong(), any(), any(), any(),
                any(), any());
    }

    @Test
    void elHashDeLaUrlEsEstableYDistingueUrlsParecidas() {
        // La clave de la memoria de orígenes. Si dejara de ser estable, cada arranque volvería a bajar
        // el catálogo entero; si no distinguiera, una ficha acabaría con la foto de otra.
        assertThat(ImageMirrorService.hashDeUrl(URL)).isEqualTo(ImageMirrorService.hashDeUrl(URL));
        assertThat(ImageMirrorService.hashDeUrl(URL)).hasSize(64);
        assertThat(ImageMirrorService.hashDeUrl(URL))
                .isNotEqualTo(ImageMirrorService.hashDeUrl(URL.replace("123", "124")));
        // Los espacios de sobra no cuentan: la misma URL con un salto de línea pegado es la misma URL.
        assertThat(ImageMirrorService.hashDeUrl("  " + URL + "\n")).isEqualTo(ImageMirrorService.hashDeUrl(URL));
    }

    @Test
    void elProveedorNuncaRecibeMasDescargasALaVezDeLasPermitidas() throws Exception {
        // Dos permisos por host: por muchas que se lancen, el proveedor nunca ve más de dos a la vez.
        // Sin este techo, escalar el espejado deja de ser un problema de rendimiento y pasa a ser uno
        // de acceso — 1688 corta a quien insiste, y entonces no hay catálogo que espejar.
        //
        // Se coordina con cerrojos y no con esperas de reloj: las seis tareas entran y se quedan
        // retenidas hasta que la prueba las suelta, así el recuento es exacto y no depende del tiempo.
        LimitadorDeDescargasPorOrigen limite = new LimitadorDeDescargasPorOrigen(2, 30);
        CountDownLatch dosDentro = new CountDownLatch(2);
        CountDownLatch suelta = new CountDownLatch(1);
        AtomicInteger entraron = new AtomicInteger();
        List<Thread> hilos = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            hilos.add(Thread.ofVirtual().start(() -> {
                try {
                    limite.conPermiso(URL, () -> {
                        entraron.incrementAndGet();
                        dosDentro.countDown();
                        suelta.await();
                        return null;
                    });
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        assertThat(dosDentro.await(30, TimeUnit.SECONDS)).isTrue();
        // Con dos retenidas dentro, las otras cuatro están esperando turno: ni una más ha pasado.
        assertThat(limite.enVuelo().get("cbu01.alicdn.com")).isEqualTo(2);
        assertThat(entraron.get()).isEqualTo(2);

        suelta.countDown();
        for (Thread h : hilos) {
            h.join();
        }
        // Al soltar, todas acaban pasando: el techo regula el ritmo, no descarta trabajo.
        assertThat(entraron.get()).isEqualTo(6);
    }

    @Test
    void cadaProveedorTieneSuPropioTechoYUnoLentoNoFrenaAlOtro() throws Exception {
        LimitadorDeDescargasPorOrigen limite = new LimitadorDeDescargasPorOrigen(1, 5);
        // Con el único permiso de alicdn cogido, otro host tiene que poder pasar igualmente.
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch suelta = new CountDownLatch(1);
        Thread ocupa = Thread.ofVirtual().start(() -> {
            try {
                limite.conPermiso(URL, () -> {
                    dentro.countDown();
                    suelta.await();
                    return null;
                });
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();
        String otroProveedor = limite.conPermiso("https://otro-proveedor.example/foto.jpg", () -> "pasó");
        assertThat(otroProveedor).isEqualTo("pasó");
        suelta.countDown();
        ocupa.join();
    }

    @Test
    void laSegundaPasadaDejaLaOriginalSiComprimirNoLaAligera() {
        // Las fotos ya optimizadas en origen no mejoran al recomprimir. Reescribirlas costaría una
        // subida y cambiaría su URL sin ganar nada — y una URL que cambia invalida lo que el borde
        // tuviera guardado.
        ProductImageEntity img = ProductImageEntity.builder().build();
        img.setId(UUID.randomUUID());
        img.setCdnUrl("https://img.nx036.com/product-images/media/ab/abcd.webp");
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of(img));
        when(storage.bytesFromPublicUrl(img.getCdnUrl())).thenReturn(new byte[0]);

        int aligeradas = service.comprimirPendientesBatch(10);

        assertThat(aligeradas).isZero();
        verify(imageRepository).marcaSinComprimir(eq(img.getId()), any(Instant.class));
        verify(storage, never()).upload(any(), any(), any());
    }

    @Test
    void sinNadaPendienteLaSegundaPasadaNoHaceNada() {
        when(imageRepository.findPendientesDeComprimir(any())).thenReturn(List.of());

        assertThat(service.comprimirPendientesBatch(10)).isZero();

        verify(storage, never()).bytesFromPublicUrl(any());
        verify(imageRepository, never()).marcaSinComprimir(any(), any());
    }
}
