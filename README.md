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
### 3. Build the JAR (If not present)
If `P2PVideoApp.jar` is not in the root directory:
1. Export the project as a **Runnable JAR** from Eclipse or IntelliJ.
2. Name it `P2PVideoApp.jar`.
3. Place it in the root folder (next to `Dockerfile`).

### X Server Configuration (Required for Windows)
Since the app has a GUI running inside Docker, you need an X Server to see it.

1. Install [VcXsrv](https://sourceforge.net/projects/vcxsrv/).
2. Run **XLaunch** with these settings:
   - **Display number:** `0`
   - **Extra settings:** Check **"Disable access control"** (Crucial step!).
3. The `docker-compose.yml` is already configured to use `DISPLAY=host.docker.internal:0.0`.

## How to Run

Open a terminal in the project folder and run:

```bash
docker-compose up --build
