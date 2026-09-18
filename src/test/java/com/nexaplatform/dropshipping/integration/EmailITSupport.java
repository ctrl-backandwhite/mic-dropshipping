package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Banco de pruebas de los correos SALIENTES: arranca el contexto completo (con Postgres de
 * Testcontainers) y sustituye únicamente la boca del SMTP por un doble, de modo que el
 * {@link MimeMessage} que la aplicación habría puesto en el cable queda capturado y se puede abrir
 * como lo abriría el cliente de correo: asunto, cuerpo, adjuntos y codificación reales.
 *
 * <p>Por qué se dobla {@link JavaMailSender} y no se levanta un SMTP de verdad (Mailpit/GreenMail):
 * lo que hay que certificar es el CONTENIDO del mensaje, no el diálogo SMTP. Con el doble se inspecciona
 * el mismo objeto que se entregaría al servidor, sin depender de un contenedor extra ni de esperas.
 *
 * <p>Los mensajes capturados se vuelven a SERIALIZAR y a leer ({@link #reserializar}) antes de
 * examinarlos: así se comprueba el correo tal y como viaja (cabeceras actualizadas, cuerpo codificado
 * en quoted-printable/base64), que es donde se rompen los acentos y el chino, y no el objeto en memoria.
 */
// El indicador de salud del correo se construye a partir de los JavaMailSenderImpl del contexto; al
// sustituir el sender por un doble no queda ninguno y el arranque fallaba con "'beans' must not be
// empty". Aquí no se mide la salud de nada, así que se apaga ese indicador.
@TestPropertySource(properties = "management.health.mail.enabled=false")
@Import(EmailITSupport.PlanificadorInerte.class)
abstract class EmailITSupport extends BaseIntegration {

    /**
     * El envío real lo dispara un {@code @Scheduled(fixedDelay = 15s)}. Con el planificador vivo, un
     * barrido de fondo podía llevarse el correo del test a mitad de la prueba —o encontrarse el doble ya
     * reseteado entre tests y dejar la fila aplazada—, con lo que el resultado dependía del reloj. Aquí
     * el planificador se sustituye por uno que ACEPTA las tareas y no las ejecuta nunca: los barridos
     * los provoca el test llamando a {@link EmailQueueService#dispatchPending()} cuando toca.
     */
    @TestConfiguration
    static class PlanificadorInerte {

        @Bean
        TaskScheduler taskScheduler() {
            ScheduledThreadPoolExecutor inerte = new ScheduledThreadPoolExecutor(1);
            // Apagado + política de descarte: toda tarea programada se rechaza en silencio (no se ejecuta
            // ni revienta a quien la programa).
            inerte.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
            inerte.shutdown();
            return new ConcurrentTaskScheduler(inerte);
        }
    }

    /** Sesión sin propiedades: solo hace falta para construir/parsear mensajes, nunca para conectar. */
    private static final Session SESION = Session.getInstance(new Properties());

    /** Contador para direcciones IP distintas por petición (ver {@link #ipDeCliente()}). */
    private static final AtomicInteger CLIENTE = new AtomicInteger();

    @MockitoBean
    protected JavaMailSender mailSender;

    /**
     * El bucket de imágenes también se dobla: el correo de factura lee del storage los bytes de cada foto
     * para adjuntarla, y en los tests no hay MinIO. Por defecto devuelve {@code null} (= "no hay imagen"),
     * y cada prueba que necesite una foto real la programa.
     */
    @MockitoBean
    protected ObjectStorageService objectStorage;

    @Autowired
    protected EmailQueueService emailQueue;

    @BeforeEach
    void prepararBuzon() {
        // Cada llamada crea un mensaje NUEVO: si se devolviera siempre el mismo, dos correos del mismo
        // test escribirían encima del anterior y las aserciones mirarían un mensaje mezclado.
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(SESION));
    }

    /* ==================== disparo del envío ==================== */

    /** Provoca el barrido de la cola: es lo que convierte las filas PENDING en correos enviados. */
    protected void despacharCola() {
        emailQueue.dispatchPending();
    }

    /* ==================== lectura del buzón ==================== */

    /** Todos los mensajes entregados al servidor, tal y como viajarían por el cable. */
    protected List<MimeMessage> correosEnviados() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        // atLeast(0): aquí no se verifica un número de envíos, solo se recogen los que haya habido.
        verify(mailSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().map(EmailITSupport::reserializar).toList();
    }

    /** Los correos dirigidos a un destinatario concreto, en orden de envío. */
    protected List<MimeMessage> correosPara(String destinatario) {
        List<MimeMessage> suyos = new ArrayList<>();
        for (MimeMessage msg : correosEnviados()) {
            if (destinatario.equalsIgnoreCase(destinatarioDe(msg))) {
                suyos.add(msg);
            }
        }
        return suyos;
    }

    /** El único correo que debería haber recibido ese destinatario; falla si hay cero o más de uno. */
    protected MimeMessage unicoCorreoPara(String destinatario) {
        List<MimeMessage> suyos = correosPara(destinatario);
        if (suyos.size() != 1) {
            throw new AssertionError("Se esperaba exactamente 1 correo para " + destinatario + " y hay "
                    + suyos.size() + " (asuntos: " + suyos.stream().map(EmailITSupport::asuntoDe).toList() + ")");
        }
        return suyos.get(0);
    }

    protected static String destinatarioDe(MimeMessage msg) {
        try {
            return msg.getRecipients(MimeMessage.RecipientType.TO)[0].toString();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el destinatario del correo", e);
        }
    }

    protected static String asuntoDe(MimeMessage msg) {
        try {
            return msg.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el asunto del correo", e);
        }
    }

    protected static String remitenteDe(MimeMessage msg) {
        try {
            return msg.getFrom()[0].toString();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el remitente del correo", e);
        }
    }

    /** Cuerpo HTML del correo (concatenado si viniera troceado), ya decodificado a texto. */
    protected static String cuerpoHtml(MimeMessage msg) {
        StringBuilder html = new StringBuilder();
        recorrer(msg, parte -> {
            try {
                if (parte.isMimeType("text/html")) {
                    html.append(String.valueOf(parte.getContent()));
                }
            } catch (Exception e) {
                throw new IllegalStateException("No se pudo leer una parte del correo", e);
            }
        });
        return html.toString();
    }

    /** Partes que el mensaje lleva pegadas (imágenes inline y ficheros adjuntos), sin el cuerpo HTML. */
    protected static List<Part> partesAdjuntas(MimeMessage msg) {
        List<Part> partes = new ArrayList<>();
        recorrer(msg, parte -> {
            try {
                if (!parte.isMimeType("text/html") && !parte.isMimeType("text/plain")) {
                    partes.add(parte);
                }
            } catch (Exception e) {
                throw new IllegalStateException("No se pudo leer una parte del correo", e);
            }
        });
        return partes;
    }

    /** La imagen incrustada con ese Content-ID, o null si el correo no la lleva dentro. */
    protected static Part parteConCid(MimeMessage msg, String cid) {
        for (Part parte : partesAdjuntas(msg)) {
            try {
                String[] cabecera = parte.getHeader("Content-ID");
                if (cabecera != null && cabecera.length > 0 && ("<" + cid + ">").equals(cabecera[0])) {
                    return parte;
                }
            } catch (Exception e) {
                throw new IllegalStateException("No se pudo leer el Content-ID de una parte", e);
            }
        }
        return null;
    }

    /** Ficheros adjuntos propiamente dichos (disposición ATTACHMENT), p. ej. el PDF de la factura. */
    protected static List<Part> ficherosAdjuntos(MimeMessage msg) {
        List<Part> adjuntos = new ArrayList<>();
        for (Part parte : partesAdjuntas(msg)) {
            try {
                if (Part.ATTACHMENT.equalsIgnoreCase(parte.getDisposition())) {
                    adjuntos.add(parte);
                }
            } catch (Exception e) {
                throw new IllegalStateException("No se pudo leer la disposición de una parte", e);
            }
        }
        return adjuntos;
    }

    /** Tipo MIME declarado de una parte ("image/jpeg", "application/pdf"…). */
    protected static String tipoMimeDe(Part parte) {
        try {
            return parte.getContentType();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el tipo de una parte", e);
        }
    }

    /** Disposición de la parte: {@code inline} (imagen del cuerpo) o {@code attachment} (fichero). */
    protected static String disposicionDe(Part parte) {
        try {
            return parte.getDisposition();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer la disposición de una parte", e);
        }
    }

    /** Nombre con el que el cliente de correo enseñará/guardará la parte. */
    protected static String nombreDe(Part parte) {
        try {
            return parte.getFileName();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el nombre de una parte", e);
        }
    }

    /** Bytes de una parte, para comprobar que el adjunto no viaja vacío. */
    protected static byte[] bytesDe(Part parte) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            parte.getInputStream().transferTo(salida);
            return salida.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudieron leer los bytes de un adjunto", e);
        }
    }

    /* ==================== utilidades ==================== */

    /**
     * Extrae el primer valor que casa con el patrón dentro del cuerpo (enlaces de activación, tokens de
     * baja…). Devuelve null si no aparece, para que el test pueda afirmar también la ausencia.
     */
    protected static String extraer(String texto, String regex) {
        Matcher m = Pattern.compile(regex).matcher(texto);
        return m.find() ? m.group(1) : null;
    }

    /**
     * El mismo texto tal y como acaba escrito en el HTML. Thymeleaf escapa lo que pinta, así que un
     * literal con "&" ("Ver pedido & factura") aparece en el cuerpo como {@code &amp;}: comparar contra
     * el texto crudo daría un fallo que no lo es.
     */
    protected static String comoSeVeEnElHtml(String texto) {
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Una IP distinta por petición. El alta y el reenvío del código están limitados a 5 por hora POR IP;
     * sin esto, a partir del sexto caso de la suite el servidor respondería 429 y el test estaría
     * midiendo el limitador en vez del correo. Cada caso es, de hecho, un cliente distinto.
     */
    protected static String ipDeCliente() {
        return "203.0.113." + (CLIENTE.incrementAndGet() % 250 + 1);
    }

    /** PNG real (no unos bytes cualesquiera): la miniatura del correo se genera con ImageIO. */
    protected static byte[] pngDePrueba(int lado) {
        BufferedImage imagen = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        try {
            g.setColor(new Color(0x0C4A97));
            g.fillRect(0, 0, lado, lado);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            ImageIO.write(imagen, "png", salida);
            return salida.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar la imagen de prueba", e);
        }
    }

    /* ==================== interioridades ==================== */

    /**
     * Escribe el mensaje y lo vuelve a leer. Es lo que hace de verdad el servidor SMTP, así que lo que se
     * examina después es el correo REAL —con sus cabeceras ya calculadas y su cuerpo codificado—, no el
     * objeto a medio construir que quedó en memoria.
     */
    private static MimeMessage reserializar(MimeMessage original) {
        try {
            ByteArrayOutputStream cable = new ByteArrayOutputStream();
            original.writeTo(cable);
            return new MimeMessage(SESION, new ByteArrayInputStream(cable.toByteArray()));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo serializar el correo capturado", e);
        }
    }

    /** Recorre en profundidad todas las partes hoja del mensaje. */
    private static void recorrer(Part parte, Consumer<Part> visitante) {
        try {
            Object contenido = parte.getContent();
            if (contenido instanceof Multipart multiparte) {
                for (int i = 0; i < multiparte.getCount(); i++) {
                    BodyPart hija = multiparte.getBodyPart(i);
                    recorrer(hija, visitante);
                }
                return;
            }
        } catch (Exception e) {
            // Una parte binaria sin DataContentHandler (p. ej. application/pdf) no se puede "abrir":
            // no es multiparte, así que se trata como hoja y se visita igual.
            visitante.accept(parte);
            return;
        }
        visitante.accept(parte);
    }
}
