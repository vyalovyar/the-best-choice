# build stage compiles the Maven module
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
# only build the voting module and its dependencies
RUN mvn clean package -DskipTests -pl voting -am

# runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/voting/target/voting-1.0.0.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]