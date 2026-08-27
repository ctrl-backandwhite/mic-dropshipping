# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
# Las dependencias, en su propia capa: así se reutiliza entre construcciones
# mientras el pom.xml no cambie, que es lo que hace que esto tarde 10 minutos en
# vez de 20. Al cambiar el pom, la capa se invalida y se vuelven a resolver.
#
# Este patrón fue el que ocultó el fallo del 27-ago-2026 —una dependencia nueva no
# llegaba al jar— pero el problema no era el patrón: era que NADIE COMPROBABA el
# resultado. La verificación de más abajo cierra ese hueco, así que se puede
# conservar la velocidad sin volver a publicar imágenes incompletas.
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B dependency:go-offline
COPY src ./src
# El empaquetado, con el mismo repositorio montado.
#
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
RUN for LIB in spring-session-core spring-session-data-redis spring-boot-session \
               spring-data-redis \
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
