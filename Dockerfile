# =============================================================================
# Redis Messaging Patterns - Backend Dockerfile
# Multi-stage build for Spring Boot application
# =============================================================================

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copy pom.xml first to cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
COPY lua ./lua
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Add non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the built JAR
COPY --from=build /app/target/redis-messaging-patterns-*.jar app.jar

# Copy Lua scripts
COPY --from=build /app/lua ./lua

# Create logs directory
RUN mkdir -p /app/logs && chown -R spring:spring /app

USER spring

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

