FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends fuse3 libfuse2t64 \
    && rm -rf /var/lib/apt/lists/*
COPY koonj-fuse.jar /app/koonj-fuse.jar
ENTRYPOINT ["java", "-jar", "/app/koonj-fuse.jar"]
