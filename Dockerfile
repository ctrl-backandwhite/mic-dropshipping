FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

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
