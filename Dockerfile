# Multi-stage Dockerfile for building and deploying backend + UI
# This Dockerfile builds the Angular UI and Spring Boot backend together

# Stage 1: Build Angular UI
FROM node:20.16.0-alpine AS ui-builder

WORKDIR /app/ui

# Configure npm for better performance and reliability
RUN npm config set fetch-retries 5 && \
    npm config set fetch-retry-mintimeout 20000 && \
    npm config set fetch-retry-maxtimeout 120000 && \
    npm config set progress true && \
    npm config set loglevel info

# Copy UI package files
COPY ui/package*.json ./

# Install dependencies
# Note: For BuildKit support, use: DOCKER_BUILDKIT=1 docker build ...
# With BuildKit, cache mount can be used: --mount=type=cache,target=/root/.npm
RUN npm ci --prefer-offline --no-audit --progress --verbose

# Copy UI source code (explicitly copy all necessary files)
COPY ui/src ./src
COPY ui/public ./public
COPY ui/angular.json ./
COPY ui/tsconfig*.json ./

# Verify critical files are copied
RUN echo "Checking source files..." && \
    ls -la src/app/ && \
    test -f src/app/app.routes.ts && echo "✅ app.routes.ts found" || (echo "❌ app.routes.ts MISSING" && exit 1) && \
    test -f src/app/app.config.ts && echo "✅ app.config.ts found" || (echo "❌ app.config.ts MISSING" && exit 1)

# Build Angular application for production
# Increase Node memory limit for build process
RUN NODE_OPTIONS="--max-old-space-size=4096" npm run build

# Stage 2: Build Spring Boot backend with Maven
FROM maven:3.9-eclipse-temurin-17 AS backend-builder

WORKDIR /app

# Copy Maven wrapper and parent POM
COPY pom.xml mvnw ./
COPY .mvn .mvn

# Copy all module POMs (Maven needs them to parse the parent POM)
COPY ui/pom.xml ./ui/
COPY backend/pom.xml ./backend/
COPY simulator/pom.xml ./simulator/

# Copy UI source files (needed for frontend-maven-plugin to run npm install)
# The plugin will install Node/npm and run npm install, but we'll use pre-built dist
COPY ui/package*.json ./ui/
COPY ui/angular.json ./ui/
COPY ui/tsconfig*.json ./ui/
COPY ui/src ./ui/src
COPY ui/public ./ui/public

# Copy pre-built UI dist (this will be overwritten by Maven but that's okay)
COPY --from=ui-builder /app/ui/dist ./ui/dist

# Copy backend source
COPY backend/src ./backend/src

# Build the backend (Maven will rebuild UI but that's acceptable)
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

# Set Spring profile to production
ENV SPRING_PROFILES_ACTIVE=prod

# Set default log directory (can be overridden via environment variable)
ENV LOG_DIR=/data/app/longvin-trading/logs

# Set default FIX base directory for QuickFIX/J logs and store
ENV FIX_BASE_DIR=/data/app/longvin-trading/quickfix

# Set timezone to America/New_York (Eastern Time)
ENV TZ=America/New_York

# Create log and FIX directories with proper permissions
RUN mkdir -p /data/app/longvin-trading/logs && \
    mkdir -p /data/app/longvin-trading/quickfix/store && \
    mkdir -p /data/app/longvin-trading/quickfix/log && \
    chmod -R 755 /data/app/longvin-trading

# Run the application
# Pass LOG_DIR and timezone as system properties (-D) so logback can read them reliably
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -DLOG_DIR=${LOG_DIR} -Duser.timezone=America/New_York -jar app.jar"]

