# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY . /app

RUN set -eux; \
	if [ -d ./session ]; then \
		mvn -B -pl session -am clean package -DskipTests; \
	else \
		mkdir -p /tmp/build_root; \
		cp -r /app /tmp/build_root/session; \
		# Create a minimal parent POM matching session/pom.xml's parent coords
		cat > /tmp/build_root/pom.xml <<'PARENT_POM'
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.thebestchoice</groupId>
  <artifactId>the-best-choice</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
</project>
PARENT_POM
		cd /tmp/build_root; \
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