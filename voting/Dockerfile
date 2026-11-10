FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY gateway/pom.xml gateway/
COPY session/pom.xml session/
COPY voting/pom.xml voting/

RUN MAVEN_OPTS="-Dhttps.protocols=TLSv1.2 -Dhttp.keepAlive=false" \
    mvn dependency:go-offline -pl voting -am

COPY . .

RUN MAVEN_OPTS="-Dhttps.protocols=TLSv1.2 -Dhttp.keepAlive=false" \
    mvn clean package -DskipTests -pl voting -am

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/voting/target/voting-1.0.0.jar app.jar
EXPOSE 8082
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]