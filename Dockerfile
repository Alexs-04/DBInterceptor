# ========== STAGE 1: Build ==========
FROM docker.io/library/gradle:8.14-jdk21 AS build

WORKDIR /app
COPY . .

RUN gradle bootJar --no-daemon

# ========== STAGE 2: Run ==========
FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
