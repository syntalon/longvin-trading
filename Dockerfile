# Multi-stage Dockerfile for building and deploying backend + UI
# This Dockerfile builds the Angular UI and Spring Boot backend together

# Stage 1: Build Angular UI
FROM node:20.16.0-alpine AS ui-builder

WORKDIR /app/ui

# Copy UI package files
COPY ui/package*.json ./

# Install dependencies
RUN npm ci

# Copy UI source code
COPY ui/ ./

# Build Angular application for production
RUN npm run build

# Stage 2: Build Spring Boot backend with Maven
FROM maven:3.9-eclipse-temurin-17 AS backend-builder

WORKDIR /app

# Copy Maven wrapper and parent POM
COPY pom.xml mvnw ./
COPY .mvn .mvn

# Copy UI module POM (needed for Maven build)
COPY ui/pom.xml ./ui/

# Copy all source code (UI build output will be used from previous stage)
COPY --from=ui-builder /app/ui/dist ./ui/dist
COPY backend/pom.xml ./backend/
COPY backend/src ./backend/src

# Build the backend (Maven will use the pre-built UI from ui/dist)
# The backend pom.xml is configured to copy UI assets during build
RUN mvn clean package -DskipTests -pl backend -am

# Find the built JAR file
RUN find backend/target -name "backend-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" -exec cp {} /app/backend.jar \;

# Stage 3: Runtime image
FROM eclipse-temurin:17-jre-alpine AS runtime

# Create app directory
WORKDIR /app

# Copy the JAR file from builder stage
COPY --from=backend-builder /app/backend.jar app.jar

# Expose the application port
EXPOSE 8081

# Set JVM options for containerized environment
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

