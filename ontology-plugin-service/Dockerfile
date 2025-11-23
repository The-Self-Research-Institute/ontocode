FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copy Maven files
COPY pom.xml .

# Copy source code
COPY src ./src

# Build the application
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Expose port
EXPOSE 8087

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8087/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
