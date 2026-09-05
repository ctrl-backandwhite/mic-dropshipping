package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deja las fotos de producto en un tamaño y un peso razonables antes de guardarlas.
 *
 * <p>Por qué existe: las fotos llegan de 1688 con resolución de cámara y se guardaban tal cual. Medido
 * sobre el catálogo real el 4-sep-2026: 72.919 imágenes ocupando 18 GB, con ejemplares de 3072x4096 y
 * 4096x2304 que pesaban casi 10 MB cada uno. La ficha las enseña a unos 600 píxeles. Es decir, se
 * descargaban diez megas para pintar una miniatura, y eso lo paga el comprador en su línea móvil.
 *
 * <p>Las mismas cuatro fotos, reducidas a 1600 y guardadas en WebP, pesaron 102, 244, 647 y 59 kB —entre
 * el 0,6% y el 6,6% del original—.
 *
 * <h2>Por qué esto NO es «perder calidad»</h2>
 *
 * <p>Reducir a 1600 no quita nada que alguien pueda ver: la ficha nunca pinta la foto por encima de
 * ~1200 píxeles, ni siquiera a pantalla completa en un portátil, y la galería usa la misma imagen. Lo
 * que se tira es resolución que el navegador ya estaba descartando al dibujar, solo que después de
 * habérsela descargado entera.
 *
 * <p>La calidad 82 de WebP es el punto donde la diferencia deja de apreciarse a simple vista sobre
 * fotografía. Por debajo empiezan a verse manchas en los degradados —el cielo, una tela lisa—; por
 * encima se paga peso sin ganar nada visible.
 *
 * <h2>Lo que NO se toca</h2>
 *
 * <p>Una imagen que ya es pequeña se deja como está. Recomprimir lo que ya está comprimido sí pierde
 * calidad de verdad, porque cada pasada de compresión con pérdida se come detalle de la anterior, y en
 * una foto que ya pesa poco no hay nada que ganar a cambio.
 */
@Slf4j
@Component
public class CompresorDeImagen {

    /**
     * Lado mayor máximo. Por encima, la resolución sobrante solo sirve para que el navegador la tire
     * después de haberla descargado.
     */
    @Value("${nexadrop.storage.imagen-lado-maximo:1600}")
    private int ladoMaximo;

    /** Calidad de WebP, de 0 a 100. */
    @Value("${nexadrop.storage.imagen-calidad:0.82}")
    private float calidad;

    /**
     * Por debajo de esto no se toca nada: el ahorro sería de unos pocos kilobytes y la recompresión sí
     * costaría calidad.
     */
    @Value("${nexadrop.storage.imagen-minimo-bytes:120000}")
    private int minimoParaTocar;

    /** Lo que se guarda: los bytes finales, su tipo, y el tamaño en píxeles para poder anotarlo. */
    public record Comprimida(byte[] datos, String tipo, String contentType, int ancho, int alto) {
    }

    /**
     * Comprime si merece la pena; si no, devuelve la original intacta.
     *
     * <p>Nunca lanza: una foto que no se puede procesar —un formato raro, un fichero a medias— se guarda
     * tal cual y el producto sigue teniendo su imagen. Fallar aquí dejaría fichas sin foto, que es mucho
     * peor que una foto pesada.
     */
    public Comprimida comprimir(byte[] originales, String tipoOriginal) {
        try {
            BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(originales));
            if (imagen == null) {
                return sinTocar(originales, tipoOriginal);
            }
            int ancho = imagen.getWidth();
            int alto = imagen.getHeight();
            boolean esGrande = Math.max(ancho, alto) > ladoMaximo;
            if (!esGrande && originales.length < minimoParaTocar) {
                // Pequeña y ligera: se deja como está, pero ya sabemos sus medidas.
                return new Comprimida(originales, tipoOriginal, contentTypeDe(tipoOriginal), ancho, alto);
            }

            BufferedImage lista = esGrande
                    ? Thumbnails.of(imagen).size(ladoMaximo, ladoMaximo).keepAspectRatio(true).asBufferedImage()
                    : imagen;

            byte[] webp = aWebp(lista);
            if (webp == null || webp.length >= originales.length) {
                // Si no se pudo escribir WebP, o si pesa MÁS que el original —pasa con imágenes muy
                // pequeñas o ya optimizadas—, se conserva el original: el objetivo era aligerar.
                return new Comprimida(originales, tipoOriginal, contentTypeDe(tipoOriginal), ancho, alto);
            }
            log.debug("Imagen comprimida: {} -> {} bytes ({}x{} -> {}x{})", originales.length, webp.length,
                    ancho, alto, lista.getWidth(), lista.getHeight());
            return new Comprimida(webp, "webp", "image/webp", lista.getWidth(), lista.getHeight());
        } catch (Exception e) {
            log.warn("No se pudo comprimir la imagen ({} bytes): {}. Se guarda tal cual.",
                    originales.length, e.toString());
            return sinTocar(originales, tipoOriginal);
        }
    }

    private Comprimida sinTocar(byte[] datos, String tipo) {
        return new Comprimida(datos, tipo, contentTypeDe(tipo), 0, 0);
    }

    private static String contentTypeDe(String tipo) {
        return "image/" + ("jpg".equals(tipo) ? "jpeg" : tipo);
    }

    /** Escribe la imagen en WebP con pérdida. Devuelve {@code null} si no hay escritor disponible. */
    /*
     * NO se decodifica saltando píxeles, aunque acotaría la memoria.
     *
     * Es tentador: descomprimir ocupa `ancho x alto x 4` bytes —una foto de 4000x5000 son 80 MB—, y
     * pedirle al lector que tome uno de cada N píxeles dejaría ese consumo fijo. Se probó el 5-sep-2026
     * y se DESCARTÓ con medición: sobre una imagen de trama fina y rayas de un píxel —un tejido, que es
     * lo que hay en este catálogo— la salida daba **PSNR 15,5 dB** frente a la decodificación completa,
     * es decir, diferencia claramente visible. El salto de píxeles no promedia, así que produce aliasing
     * justo en los estampados.
     *
     * Además la división entera redondea hacia abajo: con 5000 de lado y un máximo de 1600 salía paso 3
     * y la imagen se decodificaba a 1334x1667, POR DEBAJO del objetivo.
     *
     * La memoria se acota donde no cuesta calidad: limitando cuántas imágenes se procesan a la vez
     * (`nexadrop.storage.mirror-concurrency`, ajustado por entorno). Con 4 hilos el pico son ~320 MB
     * sobre montones de 7 GB en pre y 9,8 GB en producción.
     */

    /**
     * Cerrojo del codificador nativo. UNA sola imagen se codifica a la vez en toda la JVM.
     *
     * <p>No es una precaución teórica: el 5-sep-2026 las dos réplicas de PRE se cayeron a la vez con
     * la JVM muerta por SIGSEGV dentro de la biblioteca nativa —
     * {@code C [libwebp-imageio.so+0x4a15] encode+0x55} — mientras cuatro hilos comprimían en
     * paralelo. Un fallo de segmento en código nativo NO se puede capturar desde Java: no hay
     * excepción que atrapar, el proceso entero desaparece y con él todas las peticiones que estuviera
     * atendiendo. Producción, que corría con un solo hilo, no reinició ni una vez.
     *
     * <p>Serializar aquí no es caro donde importa: lo que se serializa es solo la codificación, y la
     * descarga, la decodificación y el escalado —que es donde se va el tiempo— siguen yendo en
     * paralelo. Y es trabajo de fondo: al comprador no le espera nadie por esto.
     *
     * <p>Con el cerrojo, {@code mirror-concurrency} vuelve a ser un ajuste de rendimiento y deja de
     * ser una bomba: subirlo ya no puede tumbar el backend.
     */
    private static final ReentrantLock CERROJO_NATIVO = new ReentrantLock();

    private byte[] aWebp(BufferedImage imagen) throws Exception {
        Iterator<ImageWriter> escritores = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!escritores.hasNext()) {
            log.warn("No hay escritor de WebP registrado: las imágenes se guardarán sin comprimir.");
            return null;
        }
        CERROJO_NATIVO.lock();
        try {
            return codificaEnWebp(escritores.next(), imagen);
        } finally {
            CERROJO_NATIVO.unlock();
        }
    }

    /** La codificación en sí. Siempre bajo {@link #CERROJO_NATIVO}: toca la biblioteca nativa. */
    private byte[] codificaEnWebp(ImageWriter escritor, BufferedImage imagen) throws Exception {
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream flujo = new MemoryCacheImageOutputStream(salida)) {
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            if (parametros.canWriteCompressed()) {
                parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                // El primer tipo del escritor de WebP es el de pérdida; es el que interesa para fotos.
                parametros.setCompressionType(parametros.getCompressionTypes()[0]);
                parametros.setCompressionQuality(calidad);
            }
            escritor.setOutput(flujo);
            // Sin canal alfa: WebP con transparencia pesa más y las fotos de producto no la traen. Si la
            // imagen viniera con alfa, se compone sobre blanco al convertir a RGB.
            escritor.write(null, new IIOImage(sinAlfa(imagen), null, null), parametros);
            flujo.flush();
            return salida.toByteArray();
        } finally {
            escritor.dispose();
        }
    }

    /** Pasa la imagen a RGB. Un PNG con transparencia se compone sobre blanco, que es el fondo de la ficha. */
    private static BufferedImage sinAlfa(BufferedImage origen) {
        if (origen.getType() == BufferedImage.TYPE_INT_RGB) {
            return origen;
        }
        BufferedImage rgb = new BufferedImage(origen.getWidth(), origen.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        g.drawImage(origen, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        return rgb;
    }
}
