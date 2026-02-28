# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .

# Copy module sources from this folder (Docker build context should be the `session` folder
# when building this Dockerfile). Do NOT prefix with `session/` here.
COPY src src

# Run maven build for this module
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Копируем собранный JAR из правильной папки
COPY --from=build /app/session/target/session-1.0.0.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]