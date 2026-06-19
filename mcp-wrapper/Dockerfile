# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Прокси ЦБ РФ — передаются через --build-arg в deploy-скрипте
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ENV http_proxy=${HTTP_PROXY}
ENV https_proxy=${HTTPS_PROXY}
ENV no_proxy=${NO_PROXY}

COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle gradle/

RUN chmod +x gradlew

# Предзагрузить Gradle дистрибутив (dependencies resolution) до копирования src— пользуемся кэшем слоёв Docker
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY src src/

RUN ./gradlew bootJar --no-daemon -x test

# Чистим прокси из runtime-образа
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app \
    && chown -R app:app /app

COPY --from=build /app/build/libs/*.jar app.jar

USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
