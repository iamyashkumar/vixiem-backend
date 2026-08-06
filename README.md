# Vixiem Backend — API Monitoring & Intelligence Engine

High-performance, reactive Spring Boot 3 & MongoDB backend for **Vixiem** — the real-time API monitoring, endpoint health check, and AI-powered log analysis platform.

## 🚀 Features

- **Real-Time Endpoint Health Monitoring**: Automated, scheduled ping checks (default 60s cycle) with latency tracking and status transition alerts.
- **SSRF & DNS Rebinding Security**: Public IP validation preventing monitoring probes from targeting private, loopback, or internal cloud infrastructure.
- **OAuth2 Google Authentication**: Signature and audience-verified Google ID Token verification via Google API Client Libraries.
- **JWT & HTTP-Only Cookie Authentication**: Secure state handling with short-lived access tokens and persistent refresh token rotation.
- **Multi-Channel Alert Dispatch**: Email and Discord webhook alerts triggered automatically upon service downtime and recovery.
- **AI Error Analysis & Quota Rate Limiting**: Atomic MongoDB quota reservation protecting Groq AI analysis endpoints from abuse.
- **SSE Event Streaming**: Server-Sent Events broadcasting live log entries to authenticated dashboard clients.

---

## 🛠️ Technology Stack

- **Java Version**: Java 17
- **Framework**: Spring Boot 3.4.2 (Spring Security, Spring Web, Spring Data MongoDB, Spring Mail)
- **Database**: MongoDB (Atlas / Self-Hosted)
- **Authentication**: JJWT (0.11.5), Google API Client (`google-api-client`)
- **AI Integration**: Groq API (DeepSeek-R1 / Llama 3 models)

---

## ⚙️ Environment Configuration (`.env`)

Copy `.env.template` to `.env` in the backend root directory before starting the application:

```env
SPRING_DATA_MONGODB_URI=mongodb+srv://<username>:<password>@cluster0.mongodb.net/vixiem
JWT_SECRET=your_base64_encoded_64_byte_jwt_secret_key_here
GOOGLE_CLIENT_ID=your_google_oauth_client_id.apps.googleusercontent.com
GROQ_API_KEY=your_groq_api_key_here
RESEND_API_KEY=re_your_resend_api_key_here
CORS_ALLOWED_ORIGINS=https://vixiem.vercel.app,http://localhost:5173
APP_COOKIE_SECURE=true
APP_COOKIE_SAME_SITE=None
```

---

## 💻 Local Development Setup

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/iamyashkumar/vixiem-backend.git
   cd vixiem-backend
   ```

2. **Configure Environment Variables**:
   ```bash
   cp .env.template .env
   # Edit .env with your credentials
   ```

3. **Build & Run**:
   ```bash
   ./mvnw clean spring-boot:run
   ```

---

## 🔒 Security & Architecture Standards

- **No Hardcoded Credentials**: Default seeding is strictly gated behind `@Profile({"dev", "test"})`.
- **SSRF Mitigations**: `HealthCheckService.validatePublicHttpUrl()` validates resolve IPs against private RFC 1918 / 6598 ranges.
- **Atomic Quotas**: `AiQuotaService` uses MongoDB `findAndModify` atomic operations for race-free rate limiting.
