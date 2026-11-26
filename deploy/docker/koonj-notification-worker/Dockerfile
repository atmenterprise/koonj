FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="koonj-notification-worker"

RUN addgroup -S koonj && adduser -S -G koonj -h /opt/koonj koonj
WORKDIR /opt/koonj
COPY koonj-notification-worker.jar app.jar
USER koonj

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/opt/koonj/app.jar"]
