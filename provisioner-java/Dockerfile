# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
# Download dependencies first (layer-cached separately from source)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -q -DskipTests

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/target/provisioner-*.jar provisioner.jar

# Templates and config are mounted at runtime (see docker-compose.yml)
ENTRYPOINT ["java", "-jar", "/app/provisioner.jar"]
