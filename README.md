# P2P Video Streaming & Overlay Network Simulation

This project implements a Peer-to-Peer (P2P) video streaming application in Java using a custom overlay network simulated with Docker. It demonstrates traffic routing between isolated subnets, video synchronization, and GUI integration using VLCJ.

## Features

- **P2P Video Streaming:** Peers can stream video content to each other via RTSP/TCP.
- **Overlay Network:** Simulates a realistic topology with 3 distinct subnets (`172.20.x`, `172.21.x`, `172.22.x`) and a central software router.
- **Custom Routing:** Automated `ip route` configuration allows communication across isolated Docker networks.
- **GUI in Docker:** Java Swing interface running inside containers, forwarded to the host machine via X11.

##  Requirements & Dependencies

To run this project, you need the following dependencies. The required JAR files are included in the `lib/` folder:

- **Java 17** (Eclipse Temurin)
- **Docker & Docker Compose**
- **VLCJ Libraries** (Found in `lib/`):
  - `vlcj-4.7.1.jar`
  - `vlcj-natives-4.1.0.jar`
  - `jna-5.8.0.jar`
  - `jna-platform-5.8.0.jar`

> **Note:** These libraries are essential for the application to interface with the VLC media player installed inside the Docker container.
