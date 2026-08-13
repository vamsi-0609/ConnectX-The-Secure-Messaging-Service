# ConnectX - The Secure Messaging Service

ConnectX is a modern, real-time secure messaging application built with a **Spring Boot 3 (Java 21)** backend and a **React 18 + Vite + TypeScript** frontend. It provides instant messaging capabilities, real-time WebSocket communication via STOMP, user authentication with JWT, conversation management, and media attachment support.

---

## 🚀 Features

- 🔐 **Secure Authentication**: JWT (JSON Web Token) authentication with stateless security filtering and BCrypt password hashing.
- ⚡ **Real-Time Communication**: Full-duplex messaging powered by Spring WebSocket with STOMP sub-protocol and SockJS fallback.
- 💬 **Conversation Management**: Direct messaging (1-on-1) and group channel setup with unread badges, typing indicators, and message timestamps.
- 📁 **File & Media Sharing**: Image and document upload capabilities stored via dedicated upload handling.
- 🎨 **Modern Sleek Interface**: Dark-mode glassmorphic UI built with React 18, TypeScript, Tailwind CSS, and Lucide icons.
- 🗄️ **Flexible Database Support**: Seamless switching between in-memory **H2 Database** (for development/testing) and **MySQL** (for production persistence).

---

## 📂 Project Architecture & Structure

```
ConnectX-The Secure Messaging Service/
├── connectx-backend/          # Spring Boot 3 Backend Service (Java 21)
│   ├── src/main/java/          # Application Controllers, Services, Models & Security Config
│   ├── src/main/resources/     # application.yml configuration & profile properties
│   └── pom.xml                 # Maven dependencies & plugins
├── connectx-frontend/         # React 18 Web Frontend (TypeScript + Vite + Tailwind CSS)
│   ├── src/                    # Components, API Clients, STOMP WebSocket Client, Hooks
│   ├── index.html              # Single Page App HTML Entry Point
│   ├── package.json            # NPM dependencies & build scripts
│   └── vite.config.ts          # Vite build configuration
├── ConnectX_Professional_Architecture_Design_Plan.docx  # Project Architecture Plan
└── .gitignore                  # Git ignore rules for mono-repo
```

---

## 🛠️ Tech Stack

### **Backend (`connectx-backend`)**
- **Language**: Java 21
- **Framework**: Spring Boot 3.3.2
- **Security**: Spring Security, JJWT (`0.12.5`)
- **Data Access**: Spring Data JPA / Hibernate
- **Real-Time**: Spring WebSocket + STOMP
- **Database**: H2 (dev) / MySQL (prod)
- **Build Tool**: Maven

### **Frontend (`connectx-frontend`)**
- **Framework**: React 18
- **Language**: TypeScript 5
- **Build Tool**: Vite 5
- **Styling**: Tailwind CSS + PostCSS
- **Icons**: Lucide React
- **WebSocket Client**: `@stomp/stompjs` + `sockjs-client`

---

## 🏁 Getting Started

### Prerequisites

Ensure you have the following installed on your system:
- **Java Development Kit (JDK 21)**
- **Apache Maven 3.8+**
- **Node.js (v18.x or later)** & **npm**
- **MySQL Server** (Optional if using H2 profile)

---

### 1. Running the Backend

Navigate to the `connectx-backend` directory and start the application:

```bash
cd connectx-backend
mvn spring-boot:run
```

By default, the server runs on **`http://localhost:8080`**.
- H2 Console (when running in H2 profile): `http://localhost:8080/h2-console`
- WebSocket Endpoint: `ws://localhost:8080/ws`

---

### 2. Running the Frontend

In a separate terminal, navigate to the `connectx-frontend` directory, install dependencies, and start the Vite dev server:

```bash
cd connectx-frontend
npm install
npm run dev
```

The frontend application will be accessible at **`http://localhost:5173`** (or the URL output by Vite).

---

## 🧪 Build & Verification

- **Backend compilation**:
  ```bash
  cd connectx-backend
  mvn clean compile
  ```

- **Frontend production build**:
  ```bash
  cd connectx-frontend
  npm run build
  ```

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
