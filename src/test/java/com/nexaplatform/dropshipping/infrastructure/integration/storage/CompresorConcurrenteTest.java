package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El codificador WebP nativo, a prueba de varios hilos.
 *
 * <p>Por qué existe esta prueba: el 5-sep-2026 las DOS réplicas de PRE se cayeron a la vez. No fue una
 * excepción ni un error de negocio — fue la JVM entera muriendo por SIGSEGV dentro de la biblioteca
 * nativa mientras cuatro hilos comprimían imágenes en paralelo:
 *
 * <pre>
 *   SIGSEGV (0xb) ... Problematic frame:
 *   C  [libwebp-imageio.so+0x4a15]  encode+0x55
 * </pre>
 *
 * <p>Un fallo de segmento en código nativo no se puede capturar: no hay excepción, el proceso
 * desaparece y con él todas las peticiones que estuviera atendiendo. Producción, que corría con un solo
 * hilo, no reinició ni una vez — y esa fue la pista.
 *
 * <p>Lo que se fija aquí es que la llamada nativa siga serializada. Si alguien quita el cerrojo para
 * «ganar velocidad», estas pruebas se ponen rojas antes de que lo descubra un entorno cayéndose.
 */
@DisplayName("Compresión · el codificador nativo, uno cada vez")
class CompresorConcurrenteTest {

    private CompresorDeImagen compresor;

    @BeforeEach
    void montar() throws Exception {
        compresor = new CompresorDeImagen();
        set("ladoMaximo", 1600);
        set("calidad", 0.82f);
    }

    @Test
    @DisplayName("el cerrojo del codificador nativo sigue ahí")
    void el_cerrojo_sigue_ahi() throws Exception {
        Field f = CompresorDeImagen.class.getDeclaredField("CERROJO_NATIVO");
        assertThat(Modifier.isStatic(f.getModifiers()))
                .as("el cerrojo tiene que ser estático: si fuera por instancia, dos compresores "
                        + "distintos podrían entrar a la vez en la biblioteca nativa")
                .isTrue();
        assertThat(f.getType()).isEqualTo(ReentrantLock.class);
    }

    @Test
    @DisplayName("ocho hilos comprimiendo a la vez no rompen nada y todos devuelven imagen válida")
    void ocho_hilos_a_la_vez() throws Exception {
        int hilos = 8;
        byte[] original = jpegDe(900, 700);
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        try {
            List<Callable<byte[]>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos * 3; i++) {
                tareas.add(() -> compresor.comprimir(original, "jpg").datos());
            }
            List<Future<byte[]>> resultados = pool.invokeAll(tareas);
            for (Future<byte[]> r : resultados) {
                byte[] salida = r.get();
                assertThat(salida).isNotEmpty();
                // Que se pueda volver a leer prueba que la codificación no se pisó con otra.
                assertThat(ImageIO.read(new java.io.ByteArrayInputStream(salida)))
                        .as("cada resultado tiene que ser una imagen entera, no un trozo de otra").isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] jpegDe(int ancho, int alto) throws Exception {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        int[] pixeles = new int[ancho * alto];
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                int v = (x * 255 / ancho + y * 255 / alto) / 2;
                pixeles[y * ancho + x] = (v << 16) | (v << 8) | (255 - v);
            }
        }
        img.setRGB(0, 0, ancho, alto, pixeles, 0, ancho);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private void set(String campo, Object valor) throws Exception {
        Field f = CompresorDeImagen.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(compresor, valor);
    }
}
