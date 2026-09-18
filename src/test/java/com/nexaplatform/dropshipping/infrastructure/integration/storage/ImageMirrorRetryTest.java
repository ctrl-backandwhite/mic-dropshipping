package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reintento de las imágenes que fallaron al espejarse.
 *
 * <p>Por qué existe esto: el 25-ago-2026 se cargaron 415 productos cuyas 4.835 imágenes quedaron en
 * FAILED por tiempos de espera agotados contra alicdn. Las URLs eran buenas —respondían 200 al pedirlas
 * de una en una— pero el escaparate oculta los productos sin imagen espejada, así que esos 415 estaban
 * cargados y no se podían ni ver ni comprar. Lo único que los reencolaba era el saneo del arranque, que
 * corre UNA vez, y al reintentarlas todas de golpe volvieron a fallar por lo mismo.
 *
 * <p>De ahí las dos reglas que se fijan aquí: se reintenta <b>periódicamente</b>, y cada imagen espera
 * <b>cada vez más</b> entre intento e intento, para no repetir la avalancha que causó el fallo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageMirrorRetryTest {

    @Mock
    ProductImageRepository imageRepository;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    VariantValueRepository variantValueRepository;
    @Mock
    ObjectStorageService storage;
    @Mock
    ProductIndexer productIndexer;

    @InjectMocks
    ImageMirrorService service;

    private static final Instant AHORA = Instant.parse("2026-08-26T12:00:00Z");

    @BeforeEach
    void habilitarElEspejado() throws Exception {
        set("mirrorEnabled", true);
        set("retryBaseMinutes", 5);
        set("retryMaxAttempts", 6);
        set("retryBatch", 100);
        when(storage.isReady()).thenReturn(true);
    }

    @Test
    void reencolaSoloLasQueYaCumplieronSuEspera() {
        // base 5 min: el primer intento espera 5, el segundo 10, el tercero 20...
        ProductImageEntity lista = failed(1, AHORA.minus(Duration.ofMinutes(30)));
        ProductImageEntity aunNo = failed(1, AHORA.minus(Duration.ofMinutes(2)));
        when(imageRepository.findFailedForRetry(anyInt(), anyInt())).thenReturn(List.of(lista, aunNo));

        service.requeueFailedForRetry(AHORA);

        verify(imageRepository).requeueToPending(List.of(lista.getId()));
    }

    @Test
    void laEsperaSeDuplicaConCadaIntentoFallido() {
        // Con 3 intentos a la espalda toca esperar 5 x 2^3 = 40 minutos. A los 30 todavía no le toca.
        ProductImageEntity tresIntentos = failed(3, AHORA.minus(Duration.ofMinutes(30)));
        when(imageRepository.findFailedForRetry(anyInt(), anyInt())).thenReturn(List.of(tresIntentos));

        service.requeueFailedForRetry(AHORA);

        verify(imageRepository, never()).requeueToPending(anyList());
    }

    @Test
    void alPasarLaEsperaLargaSiSeReencola() {
        ProductImageEntity tresIntentos = failed(3, AHORA.minus(Duration.ofMinutes(41)));
        when(imageRepository.findFailedForRetry(anyInt(), anyInt())).thenReturn(List.of(tresIntentos));

        service.requeueFailedForRetry(AHORA);

        verify(imageRepository).requeueToPending(List.of(tresIntentos.getId()));
    }

    /**
     * Sin tope, una URL muerta de verdad se reintentaría para siempre y gastaría el lote que necesitan las
     * recuperables. El tope lo aplica la consulta, así que aquí se fija que se le pasa.
     */
    @Test
    void noPideLasQueYaAgotaronSusIntentos() {
        when(imageRepository.findFailedForRetry(anyInt(), anyInt())).thenReturn(List.of());

        service.requeueFailedForRetry(AHORA);

        verify(imageRepository).findFailedForRetry(6, 100);
    }

    /**
     * LAS MUESTRAS DE COLOR TAMBIÉN SE REINTENTAN, y esto es lo que faltaba.
     *
     * <p>El 18-sep-2026, al subir 9.425 productos a PRE, el 96% de las muestras de color se quedaron
     * apuntando a alicdn. No era lentitud: en tres medidas separadas no se movió ni una. El barrido las
     * descarta con {@code imageMirrorFailedAt IS NULL}, el propio barrido les pone esa marca cuando
     * fallan, y este reintento solo miraba {@code imageRepository}. Así que una muestra que fallara UNA
     * vez quedaba excluida para siempre, sin registro y sin forma de recuperarla desde el panel.
     *
     * <p>Se ve en la tienda porque alicdn protege contra el enlazado: la misma imagen da 200 sin
     * referer y 403 con {@code Referer: https://pre.nx036.com/}. Sin espejar, la muestra sale rota.
     */
    @Test
    void reencolaTambienLasImagenesDeVarianteYDeMuestraDeColor() {
        service.requeueFailedForRetry(AHORA);

        verify(variantValueRepository).requeueFailed(AHORA.minus(Duration.ofMinutes(5)), 100);
        verify(variantRepository).requeueFailed(AHORA.minus(Duration.ofMinutes(5)), 100);
    }

    /** Con el espejado apagado no se toca la base: el interruptor manda sobre todo lo demás. */
    @Test
    void conElEspejadoApagadoNoHaceNada() throws Exception {
        set("mirrorEnabled", false);

        service.requeueFailedForRetry(AHORA);

        verify(imageRepository, never()).findFailedForRetry(anyInt(), anyInt());
    }

    /** Nada que reintentar no puede acabar en una llamada de reencolado con la lista vacía. */
    @Test
    void sinCandidatosNoLlamaAlReencolado() {
        when(imageRepository.findFailedForRetry(anyInt(), anyInt())).thenReturn(List.of());

        service.requeueFailedForRetry(AHORA);

        verify(imageRepository, never()).requeueToPending(anyList());
    }

    private static ProductImageEntity failed(int intentos, Instant ultimoIntento) {
        ProductImageEntity img = new ProductImageEntity();
        img.setId(UUID.randomUUID());
        img.setMirrorStatus(MirrorStatus.FAILED);
        img.setMirrorAttempts(intentos);
        img.setUpdatedAt(ultimoIntento);
        return img;
    }

    private void set(String campo, Object valor) throws Exception {
        Field f = ImageMirrorService.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(service, valor);
    }
}
