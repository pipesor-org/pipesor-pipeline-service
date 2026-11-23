FROM eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99

ARG VCS_REF=unknown
LABEL org.opencontainers.image.source="https://github.com/pipesor-org/pipesor-pipeline-service" \
      org.opencontainers.image.revision="${VCS_REF}"

ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080

RUN groupadd --gid 10001 pipesor \
    && useradd --uid 10001 --gid pipesor --no-create-home --shell /usr/sbin/nologin pipesor

WORKDIR /app
COPY --chown=10001:10001 target/pipesor-pipeline-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
