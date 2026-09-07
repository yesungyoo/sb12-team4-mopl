# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY core core
COPY infrastructure infrastructure
COPY batch batch

RUN chmod +x gradlew
RUN ./gradlew :batch:bootJar --no-daemon

# ---- Run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/batch/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
