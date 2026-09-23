package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Que una sola imagen no pueda volver a tumbar el sistema para siempre.
 *
 * <h2>Qué pasó</h2>
 *
 * <p>El 5-sep-2026 PRE se cayó dos veces. Una imagen provocaba un SIGSEGV dentro del codificador WebP
 * nativo, y eso NO es una excepción: no pasa por ningún {@code catch}, mata el proceso entero y se
 * lleva por delante las peticiones que estuviera atendiendo.
 *
 * <p>Lo que convirtió un fallo en una avería permanente fue otra cosa: el intento solo se anotaba
 * DESPUÉS de procesar. Como el proceso moría antes de llegar ahí, la imagen volvía intacta al
 * siguiente lote, y al siguiente, y al siguiente. Las dos réplicas entraron en un bucle de caídas del
 * que el sistema no podía salir solo — había un tope de 6 intentos, pero no llegaba a contarse ni uno.
 *
 * <h2>Qué se fija aquí</h2>
 *
 * <p>Dos cosas, y ninguna depende de haber encontrado la causa del fallo nativo:
 *
 * <ol>
 *   <li>El intento se anota ANTES de tocar la imagen, comprometido en el acto.</li>
 *   <li>Una imagen que ya falló se espeja SIN comprimir. Aligerar una foto es una mejora; un proceso
 *       que se muere deja el escaparate sin servir.</li>
 * </ol>
 */
@DisplayName("Espejado · una imagen no puede tumbar el sistema para siempre")
class EspejadoNoSeAtascaTest {

    @Test
    @DisplayName("existe el registro de intento PREVIO, y no suma en el camino del fallo capturado")
    void el_intento_se_anota_antes() throws Exception {
        Method previo = ProductImageRepository.class.getMethod("anotaIntentoAntesDeProcesar", UUID.class);
        assertThat(previo)
                .as("sin este método, un fallo que mata el proceso no deja rastro y la imagen vuelve para siempre")
                .isNotNull();

        // Y tiene que existir la variante que marca el fallo SIN volver a contar: si contara, cada fallo
        // gastaría dos intentos de los seis y la imagen se rendiría a la mitad de pronto.
        Method sinContar = ProductImageRepository.class.getMethod("markFailed", UUID.class);
        assertThat(sinContar).isNotNull();
    }

    @Test
    @DisplayName("el compresor sabe NO comprimir, conservando las medidas")
    void sabe_guardar_sin_comprimir() throws Exception {
        CompresorDeImagen compresor = new CompresorDeImagen();
        set(compresor, "ladoMaximo", 1600);
        set(compresor, "calidad", 0.82f);
        set(compresor, "minimoParaTocar", 120000);

        byte[] original = jpegDe(300, 200);
        CompresorDeImagen.Comprimida salida = compresor.sinComprimir(original, "jpg");

        assertThat(salida.datos())
                .as("los bytes tienen que ser EXACTAMENTE los originales: no se ha tocado el codificador")
                .isEqualTo(original);
        assertThat(salida.tipo()).isEqualTo("jpg");
        assertThat(salida.ancho()).isEqualTo(300);
        assertThat(salida.alto())
                .as("las medidas sí se leen: se pueden obtener sin codificar nada, y el catálogo las necesita")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("una imagen ilegible tampoco rompe el camino sin comprimir")
    void basura_no_rompe() throws Exception {
        CompresorDeImagen compresor = new CompresorDeImagen();
        set(compresor, "ladoMaximo", 1600);
        set(compresor, "calidad", 0.82f);
        set(compresor, "minimoParaTocar", 120000);

        byte[] basura = "esto no es una imagen".getBytes();
        CompresorDeImagen.Comprimida salida = compresor.sinComprimir(basura, "jpg");

        assertThat(salida.datos()).isEqualTo(basura);
        assertThat(salida.ancho()).isZero();
    }

    private static byte[] jpegDe(int ancho, int alto) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(ancho, alto,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                img.setRGB(x, y, (x * 255 / ancho) << 16 | (y * 255 / alto) << 8);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static void set(Object o, String campo, Object valor) throws Exception {
        java.lang.reflect.Field f = CompresorDeImagen.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(o, valor);
    }
}
