# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Real-time chat application monorepo with a Next.js 15 frontend and Spring Boot 3.5 backend. Uses Socket.IO for WebSocket communication, MongoDB for persistence, and Redis for caching/rate limiting.

## Build & Development Commands

### Quick Start (Root)
```bash
npm run dev              # Run both frontend and backend
npm run dev:frontend     # Frontend only (port 3000)
npm run dev:backend      # Backend only (ports 5001/5002)
```

### Backend (apps/backend/)
```bash
make setup-java          # Auto-install Java 21 via SDKMAN (first time)
make dev                 # Dev server with Testcontainers
make build               # Full build with tests
make build-jar           # JAR only, skip tests (fast)
make test                # Run tests only
./mvnw test -Dtest=ClassName#methodName  # Run single test
```

### Frontend (apps/frontend/)
```bash
npm run dev              # Development server
npm run build            # Production build
```

### E2E Tests (e2e/)
```bash
npx playwright test                    # Run all E2E tests
npx playwright test tests/chat.spec.js # Run specific test file
npx playwright test --headed           # Run with browser visible
```

### Monitoring Stack
```bash
make o11y-up             # Start Prometheus + Grafana
make o11y-down           # Stop monitoring
```

## Architecture

### Ports
- Frontend: 3000
- Backend REST API: 5001
- Socket.IO Server: 5002
- MongoDB: 27017
- Redis: 6379

### Backend Structure (apps/backend/src/main/java/com/ktb/chatapp/)
- **controller/** - REST endpoints (UserController, RoomController, FileController)
- **service/** - Business logic (UserService, FileService, SessionService)
- **repository/** - MongoDB data access (UserRepository, MessageRepository, RoomRepository)
- **websocket/socketio/** - Socket.IO event handling
  - **handler/** - Event handlers (ChatMessageHandler, RoomJoinHandler, MessageReadHandler)
  - **ai/** - AI streaming response handling (AiService, AiStreamHandler)
- **security/** - JWT authentication (CustomBearerTokenResolver, SessionAwareJwtAuthenticationConverter)
- **config/** - Spring configuration (SecurityConfig, SocketIOConfig, MongoConfig)
- **model/** - Domain entities (User, Message, Room, File, Session)
- **dto/** - Request/response objects

### Frontend Structure (apps/frontend/)
- **pages/** - Next.js routing
  - `index.js` - Landing/login redirect
  - `chat/index.js` - Chat room list
  - `chat/[room].js` - Individual chat room
  - `chat/new.js` - Create new room
- **components/** - React components (ChatMessages, ChatInput, UserMessage, FileMessage)
- **hooks/** - Custom React hooks
  - `useChatRoom.js` - Main chat room state management (orchestrates other hooks)
  - `useSocketHandling.js` - Socket.IO connection management
  - `useMessageHandling.js` - Message sending/receiving
  - `useInfiniteScroll.js` - Pagination via IntersectionObserver
  - `useAutoScroll.js` - Auto-scroll to new messages
- **services/** - API client (`api.js`) and Socket.IO connection (`socket.js`)
- **contexts/** - React Context (AuthContext for authentication state)

### Key Patterns
- Virtual threads enabled for concurrent request handling
- Redis TTL-based rate limiting
- AES-256 encryption for sensitive data
- JWT + OAuth2 Resource Server authentication
- Socket.IO runs on separate port (5002) from REST API (5001)
- Frontend uses custom hooks composition pattern (useChatRoom orchestrates useSocketHandling, useMessageHandling, etc.)

## Testing

Backend tests use Testcontainers for MongoDB and Redis. Test files are in `apps/backend/src/test/java/`.

E2E tests use Playwright. Test files are in `e2e/tests/` with reusable actions in `e2e/actions/`.

## Environment Variables

Backend requires `.env` file with:
- `MONGO_URI`, `REDIS_HOST`, `REDIS_PORT`
- `JWT_SECRET` (32 hex chars)
- `ENCRYPTION_KEY` (64 hex chars), `ENCRYPTION_SALT` (32 hex chars)
- `OPENAI_API_KEY` (optional, for AI features)

Use `make setup-env` to generate initial secrets.