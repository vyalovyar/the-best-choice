FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/session-1.0.0.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]