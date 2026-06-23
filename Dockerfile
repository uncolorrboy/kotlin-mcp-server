FROM eclipse-temurin:19-jdk-jammy AS build

WORKDIR /app

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x gradlew && ./gradlew installDist --no-daemon --stacktrace

FROM eclipse-temurin:19-jre-alpine

WORKDIR /app

COPY --from=build /app/build/install/ai-challenge-mcp-server /app

EXPOSE 3000

ENV HOST=0.0.0.0

CMD ["/app/bin/ai-challenge-mcp-server", "3000"]
