# =========================================================
# APS_Server backend — Spring Boot WebFlux on Java 21
# Multi-stage: build with Maven, run on slim JRE.
# =========================================================

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache deps first
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# Then sources
COPY src ./src
COPY application.yml ./application.yml
RUN mvn -B -q clean package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user
RUN groupadd -r app && useradd -r -g app app \
 && mkdir -p /app/uploads \
 && chown -R app:app /app

# Copy the fat jar (Spring Boot)
COPY --from=build /workspace/target/*.jar /app/app.jar

USER app
EXPOSE 8080

# Storage volume — uploaded files live here, persisted via docker volume
VOLUME ["/app/uploads"]

ENV JAVA_OPTS=""
ENV STORAGE_PATH=/app/uploads

# Healthcheck — basic TCP probe (the app has no actuator endpoint enabled)
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=8 \
  CMD bash -c "</dev/tcp/127.0.0.1/8080" || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
