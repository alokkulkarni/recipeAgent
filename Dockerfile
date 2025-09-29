# Stage 1: Build
FROM eclipse-temurin:23-jdk-noble AS build

WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .

RUN chmod +x ./gradlew

COPY src/ src/

# Build
RUN ./gradlew clean build

# Stage 2: Runtime
FROM eclipse-temurin:23-jre-noble AS runtime

WORKDIR /app

COPY --from=build /app/build/libs/ratatouille-1.0-SNAPSHOT.jar app.jar
CMD ["java", "-jar", "app.jar"]