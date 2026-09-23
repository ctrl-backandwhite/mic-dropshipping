package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Conexión con el bus de integración, que es un Kafka DISTINTO del interno.
 *
 * <p>Hace falta una configuración propia porque {@code spring.kafka} apunta al
 * Redpanda del entorno —el que mueve la indexación del catálogo y los correos— y
 * el bus es una instancia aparte. Mezclarlos haría que un fallo de integración o
 * un cliente ruidoso afectara a la tienda.
 *
 * <p>Se activa con {@code nexadrop.bus.enabled}. Apagado por defecto: un entorno
 * sin bus configurado debe arrancar igual, sin intentar conectar a un broker que
 * no existe.
 */
@Configuration
@ConditionalOnProperty(prefix = "nexadrop.bus", name = "enabled", havingValue = "true")
public class BusConfig {

    // defaultCandidate = false es OBLIGATORIO aquí: Spring Boot ya autoconfigura un
    // ProducerFactory y un KafkaTemplate para el Kafka interno. Sin esto habría dos beans del
    // mismo tipo y la inyección por tipo del OutboxDispatcher quedaría ambigua: la aplicación
    // no arrancaría en cuanto se encendiera el bus. Así, estos solo se entregan a quien los
    // pide por su nombre.
    @Bean(defaultCandidate = false)
    public ProducerFactory<String, Object> busProducerFactory(
            @Value("${nexadrop.bus.bootstrap-servers}") String servidores) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Confirmación de TODAS las réplicas y reintentos idempotentes: por aquí
        // viaja el catálogo certificado, y perder un mensaje significa un producto
        // que nunca llega a la tienda sin que nadie se entere.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        // Sin tipos Java en las cabeceras: los consumidores son otros servicios
        // —y algún día, otros lenguajes—, así que el mensaje debe entenderse solo.
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * Consumidor del bus. Va aparte del interno por el mismo motivo que el productor, y además con
     * su propio identificador de grupo: si compartiera grupo con la tienda, los dos se repartirían
     * los mensajes y cada uno vería solo la mitad del catálogo.
     */
    @Bean(defaultCandidate = false)
    public ConsumerFactory<String, String> busConsumerFactory(
            @Value("${nexadrop.bus.bootstrap-servers}") String servidores,
            @Value("${nexadrop.bus.group-id:nexadrop-catalogo}") String grupo) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servidores);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, grupo);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Se recibe como texto y se interpreta a mano. Deserializar directo a la clase Java ataría
        // el mensaje a un tipo concreto: al renombrar o mover esa clase, los mensajes ya publicados
        // dejarían de poder leerse.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Desde el principio: un entorno que se estrena tiene que recibir el catálogo entero, no
        // solo lo que se publique a partir de que arranque.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // La confirmación la lleva el contenedor, después de procesar. Con la automática, un fallo
        // a mitad daría el mensaje por bueno y ese producto no llegaría nunca.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Importar un producto espeja sus imágenes y puede tardar; un lote corto evita que el grupo
        // dé por muerto a este consumidor mientras aún está trabajando.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean(defaultCandidate = false)
    public ConcurrentKafkaListenerContainerFactory<String, String> busListenerContainerFactory(
            @Qualifier("busConsumerFactory") ConsumerFactory<String, String> busConsumerFactory,
            @Qualifier("busKafkaTemplate") KafkaTemplate<String, Object> busKafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factoria = new ConcurrentKafkaListenerContainerFactory<>();
        factoria.setConsumerFactory(busConsumerFactory);
        // Un solo hilo: el orden dentro de cada producto importa —la categoría antes que el
        // producto, el alta antes que la corrección— y con varios hilos se pierde.
        factoria.setConcurrency(1);
        factoria.getContainerProperties().setAckMode(AckMode.RECORD);
        factoria.setCommonErrorHandler(manejadorDeErrores(busKafkaTemplate));
        return factoria;
    }

    @Bean(defaultCandidate = false)
    public KafkaTemplate<String, Object> busKafkaTemplate(
            @Qualifier("busProducerFactory") ProducerFactory<String, Object> busProducerFactory) {
        return new KafkaTemplate<>(busProducerFactory);
    }

    /**
     * Qué hacer cuando un mensaje no se puede aplicar.
     *
     * <p>Se reintenta unas cuantas veces con un respiro entre medias, porque los motivos más
     * frecuentes son pasajeros: la categoría padre que aún no ha llegado, la base ocupada, el
     * espejado de una imagen que ha fallado. Si aun así no entra, el mensaje se aparta al tema de
     * descartes en lugar de seguir reintentándolo.
     *
     * <p>Eso último es lo importante: sin apartarlo, UN mensaje atascado detiene la partición
     * entera y ningún producto posterior llega a la tienda, sin más señal que un registro que se
     * repite. Apartado, la propagación continúa y el mensaje queda guardado para mirarlo.
     */
    private DefaultErrorHandler manejadorDeErrores(KafkaTemplate<String, Object> plantilla) {
        DeadLetterPublishingRecoverer aDescartes = new DeadLetterPublishingRecoverer(plantilla,
                (registro, excepcion) -> new TopicPartition(EventoBus.DESCARTES, -1));
        // 5 intentos separados 15 s: cerca de un minuto de margen, suficiente para que llegue una
        // categoría que venía por detrás sin dejar la cola parada un cuarto de hora.
        DefaultErrorHandler manejador = new DefaultErrorHandler(aDescartes, new FixedBackOff(15_000L, 4));
        // Un mensaje ilegible no se arregla esperando: va derecho a descartes.
        manejador.addNotRetryableExceptions(IllegalArgumentException.class);
        return manejador;
    }
}
