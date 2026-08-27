# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Un solo paso, con el repositorio de Maven en una caché de montaje.
#
# Antes iban en dos: "dependency:go-offline" en su propia capa y el empaquetado
# en otra. Esa capa intermedia se reutilizaba de la caché de la cadena de
# entrega aunque el pom.xml hubiera cambiado, así que una dependencia nueva NO
# entraba en el jar y la aplicación arrancaba sin ella. Pasó el 27-ago-2026 con
# spring-session-data-redis: estaba en el pom, no en la imagen, y las sesiones
# no se compartían entre réplicas sin que nada avisara.
#
# Con la caché de montaje, el repositorio se reaprovecha entre construcciones
# —que es lo que se quería— pero la resolución se hace SIEMPRE contra el pom.xml
# real de esta construcción.
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests package

# Comprobación de que lo declarado en el pom ha llegado de verdad al jar.
#
# Se añade porque el fallo anterior fue SILENCIOSO: la aplicación arrancó tan
# contenta sin spring-session, y solo se notó al ver que el acceso fallaba con
# varias réplicas. Una construcción que se cree correcta y produzca un artefacto
# incompleto es peor que una que falle.
# Nombres de BIBLIOTECA, no de "starter": los starter son descriptores y no
# dejan ningún jar dentro, así que buscarlos aquí siempre falla.
RUN for LIB in spring-session-core spring-session-data-redis spring-data-redis \
               lettuce-core spring-security-oauth2-client postgresql; do \
      jar tf target/*.jar | grep -q "$LIB" \
        || { echo "FALTA EN EL JAR: $LIB"; exit 1; }; \
    done && echo "Bibliotecas críticas presentes en el jar"    

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Usuario sin privilegios. El despliegue exige runAsNonRoot, así que una imagen
# que arranque como root no llega ni a levantar el contenedor: Kubernetes se
# niega antes de ejecutar nada.
RUN groupadd -g 10001 nexadrop \
 && useradd -u 10001 -g nexadrop -m -s /usr/sbin/nologin nexadrop \
 && chown -R nexadrop:nexadrop /app
USER 10001

# 18082, que es el puerto real por defecto de la aplicación
# (server.port -> PORT -> SERVER_PORT -> 18082). Antes ponía 18080 y no
# coincidía con nada: EXPOSE no abre puertos, pero sí engaña a quien lo lee.
EXPOSE 18082
ENTRYPOINT ["java","-jar","/app/app.jar"]
