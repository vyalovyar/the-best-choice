# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .

COPY session/pom.xml session/

COPY session/src session/src

RUN mvn clean package -DskipTests -pl session -am

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Копируем собранный JAR из правильной папки
COPY --from=build /app/session/target/session-1.0.0.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]