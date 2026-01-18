FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y \
    vlc \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY P2PVideoApp.jar /app/peer.jar

RUN mkdir -p /data/videos /data/buffer

ENTRYPOINT ["java", "-jar", "/app/peer.jar"]