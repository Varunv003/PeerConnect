# PeerConnect 🚀  
A lightweight file sharing tool that supports large file uploads and downloads through a custom multithreaded HTTP server and socket-based architecture.

---

## 🔧 Features

- Upload files up to **200MB** securely
- Each file is hosted on a **dedicated socket server**
- Download link includes **dynamic port binding**
- Works for **MP3, MP4, PDF, ZIP, TXT**, and more
- **Temporary hosting** — files are served from memory/disk and auto-deleted
- No frameworks — **built from scratch in Java**

---

## 🛠️ What I Implemented (Low-Level Engineering)

- ✳️ Built **custom HTTP server** using `Java HttpServer` — no Spring or frameworks used
- 🧵 Implemented **multithreaded socket servers** for serving each file in parallel
- 🔌 Used **pure socket programming** to send files byte-by-byte over TCP
- 📑 Developed a **manual multipart/form-data parser** to handle file uploads without external libraries
- 📂 Ensured file type independence (supports audio/video/binary) with raw stream handling
- 🐳 Containerized the full-stack app (backend + frontend) using Docker and Docker Compose

---

## 📦 Tech Stack

| Area         | Tech Used                                        |
|--------------|--------------------------------------------------|
| Backend      | Java (JDK 21), Raw HTTP, Sockets, Threads, Maven |
| Frontend     | Next.Js                                          |
| Packaging    | Docker, Docker Compose                           |


---

## 🚀 How to Run (Docker Compose)

> Make sure you have Docker & Docker Compose installed.

```bash
# Clone this repo
git https://github.com/Varunv003/PeerConnect

# Build and start
docker-compose up --build
```

### Stopping the Application:

To stop the application, navigate to the project directory (where the `docker-compose.yml` file is located) and run:

```bash
docker-compose down
```
