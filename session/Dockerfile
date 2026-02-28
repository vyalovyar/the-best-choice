# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY . /app

RUN set -eux; \
	if [ -d ./session ]; then \
		mvn -B -pl session -am clean package -DskipTests; \
	else \
		apt-get update && apt-get install -y --no-install-recommends curl ca-certificates; \
		mkdir -p /tmp/build_root; \
		cp -r /app /tmp/build_root/session; \
		cd /tmp/build_root; \
		curl -fsSL -o pom.xml https://raw.githubusercontent.com/Samlabb/the-best-choice/main/pom.xml || (echo "Failed to download parent pom"; exit 1); \
		mvn -B -pl session -am clean package -DskipTests; \
		mkdir -p /app/session/target; \
		cp -a /tmp/build_root/session/target/* /app/session/target/ || true; \
	fi

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/session/target/session-1.0.0.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]