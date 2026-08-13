# ConnectX Backend Service

The backend infrastructure for **ConnectX - Secure Messaging Service**, built with **Spring Boot 3** and **Java 21**.

---

## 🛠 Tech Stack & Dependencies

- **Java Version**: 21
- **Framework**: Spring Boot 3.3.2
- **Spring Modules**:
  - `spring-boot-starter-web` (REST APIs)
  - `spring-boot-starter-security` (Authentication & Authorization)
  - `spring-boot-starter-data-jpa` (Persistence)
  - `spring-boot-starter-websocket` (STOMP messaging)
  - `spring-boot-starter-validation` (Request body validation)
- **Security & Tokens**: JJWT (`0.12.5`)
- **Databases**:
  - **MySQL Connector/J** (Production database)
  - **H2 Database** (In-memory testing and development)
- **Utilities**: Project Lombok

---

## 📁 Package Overview

```
com.connectx/
├── config/              # Security, WebSocket, Web MVC & App Configurations
├── auth/                # Auth Controller, DTOs, Login/Register Services
├── user/                # User Entity, Repository, Controller & User Profile Services
├── message/             # Message Entity, Repository, WebSocket & REST Controllers
├── conversation/        # Conversation Entity, Services & Controllers
├── security/            # JwtAuthenticationFilter, JwtTokenProvider, UserDetailsService
└── exception/           # Custom exception handlers & error responses
```

---

## ⚙️ Configuration Profiles

Configuration files are located in `src/main/resources/`:
- `application.yml`: Primary application settings and profile selector.
- `application-dev.yml`: Development profile configuration.
- `application-h2.yml`: In-memory H2 database configuration (fast local dev without external MySQL database).
- `application-mysql.yml`: Production MySQL database connection profile.

---

## 🚦 How to Build and Run

### Run Application
```bash
mvn spring-boot:run
```

### Compile & Validate
```bash
mvn clean compile
```

### Package JAR
```bash
mvn clean package -DskipTests
```

---

## 🌐 Endpoints Overview

- **Auth**: `/api/v1/auth/register`, `/api/v1/auth/login`
- **Conversations**: `/api/v1/conversations`
- **Messages**: `/api/v1/messages`
- **WebSocket STOMP Broker**: `/ws` (Broker destinations: `/topic`, `/queue`, App prefix: `/app`)
