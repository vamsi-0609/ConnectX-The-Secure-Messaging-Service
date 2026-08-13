# ConnectX Web Frontend

The user interface for **ConnectX - Secure Messaging Service**, built with **React 18**, **TypeScript**, **Vite**, and **Tailwind CSS**.

---

## 🛠 Tech Stack

- **Framework**: React 18
- **Language**: TypeScript 5
- **Build System**: Vite 5
- **Styling**: Tailwind CSS, PostCSS, Autoprefixer
- **UI Icons**: Lucide React
- **WebSocket / Messaging**: `@stomp/stompjs`, `sockjs-client`

---

## 📁 Folder Structure

```
src/
├── api/              # Axios / Fetch API client modules (auth, messaging, conversations)
├── components/       # Reusable UI components (Sidebar, ChatWindow, MessageInput, Header)
├── context/          # React Context providers (AuthContext, ChatContext, ThemeContext)
├── hooks/            # Custom React hooks (useWebSocket, useAuth, useChat)
├── types/            # TypeScript interface & type definitions
├── websocket/        # STOMP WebSocket client singleton & configuration
├── App.tsx           # Main application routing and layout router
└── main.tsx          # Application DOM mounting point
```

---

## ⚙️ Environment Configuration

Environment variables can be configured in `.env`:

```env
VITE_API_BASE_URL=/api/v1
VITE_WS_BASE_URL=ws://localhost:8080/ws
```

---

## 🚀 Available Scripts

In the `connectx-frontend` directory, you can run:

### `npm run dev`
Runs the application in development mode with Hot Module Replacement (HMR). Open [http://localhost:5173](http://localhost:5173) in your browser.

### `npm run build`
Runs TypeScript type checking (`tsc`) and builds the app for production to the `dist` folder.

### `npm run preview`
Locally serves the production build from the `dist` directory.

### `npm run lint`
Runs ESLint across all TypeScript files.
