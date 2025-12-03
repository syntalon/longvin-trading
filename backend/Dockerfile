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

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

