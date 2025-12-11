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
- **controller/** - REST endpoints
- **service/** - Business logic (RedisChatService for caching)
- **repository/** - MongoDB data access
- **websocket/socketio/handler/** - Socket.IO event handlers (ChatMessageHandler)
- **security/** - JWT authentication
- **config/** - Spring configuration (RedisConfig, SocketIOConfig)
- **model/** - Domain entities
- **dto/** - Request/response objects

### Frontend Structure (apps/frontend/)
- **pages/** - Next.js routing (index.js, chat/[room].js)
- **components/** - React components (ChatInput.js, etc.)
- **services/** - API client and Socket.IO connection
- **contexts/** - React Context for state management
- **hooks/** - Custom React hooks

### Technology Stack
- **Backend**: Java 21, Spring Boot 3.5, MongoDB 8, Redis 7.2, Netty Socket.IO
- **Frontend**: Next.js 15, React 18, Tailwind CSS, Socket.IO Client
- **Testing**: JUnit 5, Testcontainers, Spring Test

### Key Patterns
- Virtual threads enabled for concurrent request handling
- Redis TTL-based rate limiting
- AES-256 encryption for sensitive data
- JWT + OAuth2 Resource Server authentication
- Socket.IO on separate port from REST API

## Testing

Backend tests use Testcontainers for MongoDB and Redis. Test files are in `apps/backend/src/test/java/`.

```bash
# Run all tests
make test

# Run specific test class
./mvnw test -Dtest=ChatMessageHandlerTest

# Run specific test method
./mvnw test -Dtest=ChatMessageHandlerTest#testMethodName
```

## Environment Variables

Backend requires `.env` file with:
- `MONGO_URI`, `REDIS_HOST`, `REDIS_PORT`
- `JWT_SECRET` (32 hex chars)
- `ENCRYPTION_KEY` (64 hex chars), `ENCRYPTION_SALT` (32 hex chars)
- `OPENAI_API_KEY` (optional, for AI features)

Use `make setup-env` to generate initial secrets.