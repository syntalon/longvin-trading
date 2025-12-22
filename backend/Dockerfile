# Multi-stage build for Spring Boot application
FROM eclipse-temurin:17-jre-alpine AS runtime

# Create app directory
WORKDIR /app

# Argument for JAR file path
ARG JAR_FILE

# Copy the JAR file
COPY ${JAR_FILE} app.jar

# Expose the application port
EXPOSE 8081

# Set JVM options for containerized environment
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Set default log directory (can be overridden via environment variable)
ENV LOG_DIR=/app/logs

# Create log directory with proper permissions
RUN mkdir -p /app/logs && chmod 755 /app/logs

# Run the application
# Pass LOG_DIR as system property (-D) so logback can read it reliably
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -DLOG_DIR=${LOG_DIR} -jar app.jar"]

