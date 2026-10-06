# ========================================================
# Stage 1: Build the multi-module Maven application
# ========================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Copy POM files first for caching Maven dependencies
COPY pom.xml .
COPY common/pom.xml common/
COPY mod-branch/pom.xml mod-branch/
COPY mod-identity/pom.xml mod-identity/
COPY mod-membership/pom.xml mod-membership/
COPY mod-schedule/pom.xml mod-schedule/
COPY app/pom.xml app/

# Download dependencies in advance
RUN mvn dependency:go-offline -B || true

# Copy all source trees
COPY common/src common/src
COPY mod-branch/src mod-branch/src
COPY mod-identity/src mod-identity/src
COPY mod-membership/src mod-membership/src
COPY mod-schedule/src mod-schedule/src
COPY app/src app/src

# Build production executable JAR (app module produces the fat jar)
RUN mvn clean package -DskipTests -B

# ========================================================
# Stage 2: Lightweight JRE 21 Runtime
# ========================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user for container security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy executable jar from builder stage
COPY --from=builder /workspace/app/target/app-1.0.0-SNAPSHOT.jar app.jar

# Configuration for Render Free Tier (512MB RAM cap)
# Render automatically sets PORT (defaults to 10000)
ENV PORT=8080 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xss512k -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT} -jar app.jar"]
