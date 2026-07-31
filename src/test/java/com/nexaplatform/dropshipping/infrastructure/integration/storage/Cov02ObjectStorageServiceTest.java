package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del almacén de objetos: cuándo está "listo", cómo se compone la URL pública y por qué leer
 * bytes de una URL nunca puede tumbar al que llama (el mirror de imágenes se apoya en eso).
 *
 * <p>El {@code MinioClient} se inyecta por reflexión porque solo lo construye {@code init()}, que
 * necesitaría un endpoint real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov02ObjectStorageServiceTest {

    private static final String BUCKET = "product-images";
    private static final String PUBLIC_URL = "http://cdn.local/product-images";

    @Mock
    private MinioClient client;

    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        service = new ObjectStorageService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "bucket", BUCKET);
        ReflectionTestUtils.setField(service, "publicUrl", PUBLIC_URL);
    }

    private void conCliente() {
        ReflectionTestUtils.setField(service, "client", client);
    }

    /* ============ arranque ============ */

    @Test
    void sinConfiguracionElAlmacenQuedaDesactivadoPeroLaAplicacionArranca() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.init();

        assertThat(service.isReady()).isFalse();
    }

    @Test
    void faltaElSecretoYNoSeConstruyeCliente() {
        // Con endpoint pero sin credencial no se puede firmar nada: mejor desactivado que a medias.
        ReflectionTestUtils.setField(service, "endpoint", "http://minio:9000");
        ReflectionTestUtils.setField(service, "secretKey", "  ");

        service.init();

        assertThat(service.isReady()).isFalse();
    }

    @Test
    void unEndpointInvalidoNoTumbaElArranqueSoloDejaElAlmacenApagado() {
        // Puerto fuera de rango: el SDK lo rechaza al construir, sin llegar a intentar conexión alguna.
        ReflectionTestUtils.setField(service, "endpoint", "http://minio:99999");
        ReflectionTestUtils.setField(service, "secretKey", "secreto");
        ReflectionTestUtils.setField(service, "accessKey", "clave");
        ReflectionTestUtils.setField(service, "region", "us-east-1");

        service.init();

        assertThat(service.isReady()).isFalse();
    }

    @Test
    void publicUrlDevuelveLaConfiguradaTalCual() {
        assertThat(service.publicUrl()).isEqualTo(PUBLIC_URL);
    }

    /* ============ subida ============ */

    @Test
    void subirDevuelveLaUrlPublicaSinDuplicarLaBarraSeparadora() {
        // La URL pública puede venir con barra final de la configuración; el resultado no puede llevar "//".
        ReflectionTestUtils.setField(service, "publicUrl", PUBLIC_URL + "///");
        conCliente();

        String url = service.upload("a/b.jpg", new byte[] {1, 2, 3}, "image/jpeg");

        assertThat(url).isEqualTo(PUBLIC_URL + "/a/b.jpg");
    }

    @Test
    void subirConservaElTipoDeContenidoIndicado() throws Exception {
        conCliente();
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);

        service.upload("fotos/a.jpg", new byte[] {1, 2, 3}, "image/jpeg");

        verify(client).putObject(captor.capture());
        assertThat(captor.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().object()).isEqualTo("fotos/a.jpg");
    }

    @Test
    void subirSinTipoDeContenidoNoRompeLaSubida() throws Exception {
        // El SDK rechaza un content-type en blanco: el servicio debe sustituirlo antes de llegar a él.
        conCliente();
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);

        String url = service.upload("c.bin", new byte[] {9}, "  ");

        assertThat(url).isEqualTo(PUBLIC_URL + "/c.bin");
        verify(client).putObject(captor.capture());
        assertThat(captor.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void subirTraduceLosNueveFallosDelSdkAUnaSolaExcepcionDeAlmacen() throws Exception {
        conCliente();
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(new IOException("red caída"));
        byte[] datos = new byte[] {1};

        assertThatThrownBy(() -> service.upload("x.jpg", datos, "image/jpeg"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("x.jpg");
    }

    /* ============ descarga ============ */

    @Test
    void descargarDevuelveLosBytesDelObjeto() throws Exception {
        conCliente();
        GetObjectResponse respuesta = mock(GetObjectResponse.class);
        when(respuesta.readAllBytes()).thenReturn(new byte[] {7, 7});
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(respuesta);

        assertThat(service.download("foto.jpg")).containsExactly(7, 7);
    }

    @Test
    void descargarUnObjetoQueNoExisteLanzaExcepcionDeAlmacen() throws Exception {
        conCliente();
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(new IOException("404"));

        assertThatThrownBy(() -> service.download("no-existe.jpg"))
                .isInstanceOf(ObjectStorageException.class);
    }

    /* ============ bytes desde la URL pública ============ */

    @Test
    void sinClienteNoHayBytesPeroTampocoNulo() {
        // Quien incrusta la imagen en el email no debe defenderse de un nulo: vacío significa "no hay foto".
        assertThat(service.bytesFromPublicUrl("http://cdn.local/product-images/a.jpg")).isEmpty();
    }

    @Test
    void urlNulaVaciaOSinUrlPublicaConfiguradaDevuelvenVacio() {
        conCliente();

        assertThat(service.bytesFromPublicUrl(null)).isEmpty();
        assertThat(service.bytesFromPublicUrl("   ")).isEmpty();

        ReflectionTestUtils.setField(service, "publicUrl", "");
        assertThat(service.bytesFromPublicUrl("http://cdn.local/product-images/a.jpg")).isEmpty();
    }

    @Test
    void unaUrlExternaNoSeIntentaLeerDeNuestroBucket() throws Exception {
        // Las imágenes que aún apuntan a alicdn no están espejadas: pedirlas al bucket sería un 404 inútil.
        conCliente();

        assertThat(service.bytesFromPublicUrl("https://cbu01.alicdn.com/img/a.jpg")).isEmpty();
        verify(client, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    void deLaUrlPublicaSeDerivaLaClaveYSeLeePorElEndpointInterno() throws Exception {
        conCliente();
        GetObjectResponse respuesta = mock(GetObjectResponse.class);
        when(respuesta.readAllBytes()).thenReturn(new byte[] {5});
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(respuesta);
        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);

        byte[] bytes = service.bytesFromPublicUrl(PUBLIC_URL + "/1688/abc/0.jpg");

        assertThat(bytes).containsExactly(5);
        verify(client).getObject(captor.capture());
        assertThat(captor.getValue().object()).isEqualTo("1688/abc/0.jpg");
    }

    @Test
    void siElObjetoNoSePuedeLeerSeDevuelveVacioEnVezDePropagarElFallo() throws Exception {
        conCliente();
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(new IOException("roto"));

        assertThat(service.bytesFromPublicUrl(PUBLIC_URL + "/roto.jpg")).isEmpty();
    }

    /* ============ listado de claves ============ */

    @Test
    void listarClavesSinClienteDevuelveConjuntoVacio() {
        assertThat(service.listKeys()).isEmpty();
    }

    @Test
    void listarClavesDevuelveLosNombresDeObjetoDelBucket() {
        conCliente();
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn("1688/abc/0.jpg");
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<>(item)));

        Set<String> claves = service.listKeys();

        assertThat(claves).containsExactly("1688/abc/0.jpg");
    }

    @Test
    void siElListadoFallaAMitadSeDevuelveLoLeidoHastaEseMomento() {
        // Verificar qué imágenes existen no puede abortar por un objeto ilegible del bucket.
        conCliente();
        Item ok = mock(Item.class);
        when(ok.objectName()).thenReturn("bien.jpg");
        when(client.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(new Result<>(ok), new Result<Item>(new IOException("corrupto"))));

        assertThat(service.listKeys()).containsExactly("bien.jpg");
    }
}
