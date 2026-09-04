package com.nexaplatform.dropshipping.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Levanta un Postgres real (Testcontainers) y lo conecta al contexto vía {@link ServiceConnection},
 * que sobreescribe {@code spring.datasource.*} automáticamente. Como no tenemos el core, esta config
 * vive en el propio repo. Se importa en las bases de integración ({@code BaseIntegration},
 * {@code PersistenceITBase}). El contenedor se reutiliza entre tests porque Spring cachea el contexto.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

    /**
     * Versión de la API de Docker con la que hablar con el demonio.
     *
     * <p>docker-java negocia por defecto la 1.32 y los demonios actuales exigen 1.44 como mínimo, así que
     * sin esto NINGÚN test de integración arranca. Y el síntoma engaña: los 51 casos de una clase fallan a
     * la vez con «Could not find a valid Docker environment», que parece un Docker apagado cuando en
     * realidad está funcionando perfectamente. Cuesta horas atribuirlo al build y no a la máquina.
     *
     * <p>Se fija AQUÍ y no en el pom por una razón concreta: ni {@code <systemPropertyVariables>} ni
     * {@code <argLine>} del plugin failsafe surtían efecto —comprobado con las dos—, porque para cuando se
     * aplican, docker-java ya ha negociado la versión. Un bloque estático de esta clase, en cambio, corre
     * al cargarla y siempre antes de que se pida el primer contenedor.
     *
     * <p>Si algún día el demonio exige más, este número sube; ponerlo por encima de lo que soporta el
     * demonio también rompe, así que no vale con «poner uno muy alto».
     */
    static {
        System.setProperty("api.version", "1.44");
    }

    /**
     * <b>La versión mayor tiene que ser la del despliegue.</b> Estos tests corrían contra Postgres 16
     * mientras el despliegue iba con el 18, y esa diferencia dejó pasar un fallo que tumbaba el arranque
     * en los dos entornos: desde PostgreSQL 17 las operaciones de MANTENIMIENTO (CREATE INDEX entre ellas)
     * se ejecutan con un search_path seguro y restringido, así que un índice de expresión que llamaba a
     * unaccent sin cualificar el esquema reventaba Liquibase. La suite entera pasaba en verde igualmente,
     * porque en la 16 ese comportamiento no existe. Si el despliegue sube de mayor, este número sube con él.
     */
    private static final String POSTGRES_IMAGE = "postgres:18-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
    }
}
