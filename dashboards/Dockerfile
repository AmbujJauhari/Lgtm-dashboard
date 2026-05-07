# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -q -DskipTests

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/target/dashboards-*.jar dashboards.jar

ENTRYPOINT ["java", "-jar", "/app/dashboards.jar"]
