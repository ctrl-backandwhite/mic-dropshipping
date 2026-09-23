package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Que encender el bus no impida arrancar.
 *
 * <p>Es el riesgo real de esta configuración: Spring Boot ya crea por su cuenta un
 * {@code KafkaTemplate} para el Kafka interno de la tienda, y varias clases lo piden por tipo. Un
 * segundo bean del mismo tipo dejaría esa petición sin resolver y la aplicación no levantaría —en
 * producción, y solo al encender el bus, que es el peor momento para descubrirlo.
 */
@DisplayName("Configuración del bus · convive con el Kafka de la tienda")
class BusConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(BusConfig.class);

    @Test
    @DisplayName("Apagado por defecto: un entorno sin bus no intenta conectarse a ningún broker")
    void apagadoPorDefecto() {
        runner.run(contexto -> {
            assertThat(contexto).hasNotFailed();
            assertThat(contexto).doesNotHaveBean("busKafkaTemplate");
            assertThat(contexto).doesNotHaveBean("busListenerContainerFactory");
        });
    }

    @Test
    @DisplayName("Encendido crea su productor y su consumidor, aparte de los de la tienda")
    void encendidoCreaLoSuyo() {
        runner.withPropertyValues("nexadrop.bus.enabled=true", "nexadrop.bus.bootstrap-servers=172.17.0.1:9122")
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    assertThat(contexto).hasBean("busKafkaTemplate");
                    assertThat(contexto).hasBean("busListenerContainerFactory");
                    assertThat(contexto.getBean("busListenerContainerFactory"))
                            .isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
                });
    }

    @Test
    @DisplayName("Con el bus encendido, pedir un KafkaTemplate por tipo SIGUE resolviendo al de la tienda")
    void pedirPorTipoNoSeVuelveAmbiguo() {
        // Esta es la prueba que importa. Sin marcar los beans del bus como no candidatos, aquí
        // habría dos del mismo tipo y el contexto fallaría: exactamente lo que tumbaría el arranque.
        runner.withPropertyValues("nexadrop.bus.enabled=true", "nexadrop.bus.bootstrap-servers=172.17.0.1:9122")
                .withUserConfiguration(QuienPideUnKafkaTemplate.class).run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    assertThat(contexto.getBean(QuienPideUnKafkaTemplate.class).plantilla)
                            .isSameAs(contexto.getBean("kafkaTemplate"));
                });
    }

    @Test
    @DisplayName("El consumidor lleva manejador de errores: un mensaje atascado no puede parar la cola")
    void llevaManejadorDeErrores() {
        // Sin él, un mensaje que no se puede aplicar se reintenta sin fin y ningún producto
        // posterior llega a la tienda, sin más señal que un registro que se repite.
        runner.withPropertyValues("nexadrop.bus.enabled=true", "nexadrop.bus.bootstrap-servers=172.17.0.1:9122")
                .run(contexto -> {
                    ConcurrentKafkaListenerContainerFactory<?, ?> factoria = (ConcurrentKafkaListenerContainerFactory<?, ?>) contexto
                            .getBean("busListenerContainerFactory");
                    // Se mira en el contenedor que la factoría produce, que es la pieza que de
                    // verdad recibe los mensajes.
                    assertThat(factoria.createContainer("catalogo.producto.certificado").getCommonErrorHandler())
                            .isNotNull();
                });
    }

    /** Alguien que pide el KafkaTemplate por tipo, como hacen el catálogo y el outbox. */
    static class QuienPideUnKafkaTemplate {
        final KafkaTemplate<String, Object> plantilla;

        QuienPideUnKafkaTemplate(KafkaTemplate<String, Object> plantilla) {
            this.plantilla = plantilla;
        }
    }
}
