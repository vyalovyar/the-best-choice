# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy root parent POM and session module so Maven can resolve the parent
# This Dockerfile expects the build context to be the repository root.
COPY pom.xml .
COPY session/pom.xml session/pom.xml
COPY session/src session/src

# Build only the session module (resolve parent from root pom)
RUN mvn -B -pl session -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Copy the assembled JAR from the build stage
COPY --from=build /app/session/target/session-1.0.0.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]