# SpringLens

> **Java-native AI knowledge platform.** Turn any document corpus into a queryable knowledge base with cited answers, retrieval quality measurement, and autonomous agents.

Built with **Java 21 · Spring Boot 4.0.3 · Spring AI 2.0 · WebFlux**

---

## What It Does

Point SpringLens at any document corpus. Ask questions in natural language. Get cited, grounded answers — with the source document and page number for every claim.

```
User: "What is the KYC updation frequency for high-risk customers?"

SpringLens: "At least once every two years from the date of account opening
             or last KYC updation."
             → Source: rbi-nbfc-kyc-guidelines.pdf, Page 41
```

The system supports three retrieval strategies, switchable per request at runtime — vector-only, hybrid (vector + full-text search), and hybrid with Cohere reranking. Quality is measured with RAGAS metrics across all strategies so you always know which one performs best on your corpus.

---

## Why This Exists

Every engineering team has the same problem — documents, runbooks, policies, contracts, specs that nobody can search effectively. SpringLens solves this the Java way: Spring Boot-native, enterprise-ready, no Python required for the core platform.

Most AI knowledge platforms are Python-first and built for demos. SpringLens is built for production — multi-tenancy, JWT auth, per-tenant cost tracking, budget enforcement, circuit breakers, reactive non-blocking throughout.

---

## Demo Data

Built and tested on RBI regulatory documents (KYC guidelines, credit card regulations, lending policies, priority sector lending) — 12 PDFs, 795 chunks, 20 golden Q&A pairs for evaluation.

**Domain-agnostic by design.** Point it at engineering runbooks, legal contracts, medical records, product specifications — the architecture is identical.

---

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │           Spring Boot 4.0           │
                    │              WebFlux                │
                    └──────────────┬──────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
     ┌────────▼────────┐  ┌────────▼────────┐  ┌────────▼───────┐
     │  JWT Auth +     │  │  RAG Pipeline   │  │  Admin / RAGAS │
     │  Multi-Tenancy  │  │  Query / Stream │  │  Evaluation    │
     │  Budget Control │  │  Chat + Memory  │  │  Dashboard     │
     └─────────────────┘  └────────┬────────┘  └────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
     ┌────────▼────────┐  ┌────────▼────────┐  ┌────────▼───────┐
     │ Retrieval       │  │ Provider Router │  │  Audit + Cost  │
     │ Strategy Layer  │  │ Groq → OpenAI   │  │  Tracking      │
     │ vector-only     │  │ → Ollama        │  │  Per-tenant    │
     │ hybrid (RRF)    │  │ (fallback chain)│  │  audit_events  │
     │ hybrid-rerank   │  └─────────────────┘  └────────────────┘
     └────────┬────────┘
              │
   ┌──────────┼──────────┐
   │          │          │
┌──▼──┐  ┌────▼──┐  ┌────▼───┐
│PGVec│  │Full   │  │Cohere  │
│tor  │  │Text   │  │Rerank  │
│Store│  │Search │  │v4.0-pro│
└─────┘  └───────┘  └────────┘
```

---

## Retrieval Strategies

Three strategies, switchable per request — no restart required.

| Strategy        | How It Works                            | Best For                      |
|-----------------|-----------------------------------------|-------------------------------|
| `vector-only`   | Cosine similarity on embeddings         | Semantic / conceptual queries |
| `hybrid`        | Vector + PostgreSQL FTS merged via RRF  | Keyword + semantic combined   |
| `hybrid-rerank` | Hybrid + Cohere cross-encoder reranking | Highest precision queries     |

```bash
POST /api/v1/ai/chat/query
{
  "message": "What is the KYC policy for NBFCs?",
  "retrievalStrategy": "hybrid-rerank",
   "conversationId": "test-conv-001",
    "memoryEnabled": true 
}
```

---

## RAGAS Evaluation — Measured Quality

RAG quality measured with RAGAS metrics across all three strategies on 20 golden Q&A pairs.

| Strategy          | Faithfulness | Answer Relevancy | Context Precision | Context Recall |
|-------------------|--------------|------------------|-------------------|----------------|
| vector-only       | 0.8915       | 0.8596           | 0.9417            | 0.8917         |
| hybrid            | 0.8989       | 0.8601           | 0.8944            | 0.8917         |
| **hybrid-rerank** | 0.8726       | **0.8686**       | **0.9708**        | 0.8917         |

**Key finding:** Cohere reranking improves context precision by 3% (0.9417 → 0.9708) — the most relevant chunks are ranked first more reliably. Context recall is identical across strategies, confirming corpus coverage is the constant; retrieval ordering is the variable.

Evaluation runs automatically persist to PostgreSQL. A regression alert fires if faithfulness drops more than 15% below the 7-day rolling average.

---

## Core Features

### RAG Pipeline
- PDF ingestion → chunking → embedding → PGVector storage
- Three retrieval strategies switchable per request at runtime
- Cohere `rerank-v4.0-pro` cross-encoder reranking
- Reciprocal Rank Fusion (RRF) for hybrid result merging
- Streaming responses via Server-Sent Events (SSE)
- Conversation memory backed by Redis (per `conversationId`)
- Stateless query endpoints — memory advisor never leaks into non-chat calls

### Multi-Provider LLM with Fallback
```
Groq → OpenAI → Ollama (local)
```
Reactive fallback chain — if Groq fails (timeout, rate limit, error), OpenAI takes over automatically. If OpenAI fails, Ollama (local) handles the request. Fully reactive — no `.block()`, no thread starvation.

### Enterprise Controls
- **JWT Authentication** — HS512, 1-hour expiry, role-based (USER / ADMIN)
- **Multi-tenancy** — every document, query, and audit row is tenant-scoped
- **Budget Enforcement** — 6 checks per request: user/tenant × daily requests / daily tokens / monthly tokens. Returns `429` for rate limits, `402` for budget exhaustion
- **Audit Logging** — every query writes to `audit_events`: tokens, cost (USD), latency, strategy, sources cited, tenant/user IDs
- **Cost Tracking** — per-query USD cost calculated and stored. ~$0.000247 per query at current rates (~₹0.02)

### RAGAS Evaluation Pipeline
- Standalone Python FastAPI service (`springlens-ragas-evaluator`) on port 8088
- Spring Boot calls it via non-blocking WebClient
- Metrics: faithfulness, answer relevancy, context precision, context recall
- Results persisted to `ragas_evaluation_run` + `ragas_evaluation_pair` tables
- Regression alert — WARN log when faithfulness drops 15%+ below rolling average
- Quality dashboard — `GET /api/v1/admin/quality` returns per-strategy averages

---

## Tech Stack

| Layer               | Technology                                                 |
|---------------------|------------------------------------------------------------|
| Language            | Java 21                                                    |
| Framework           | Spring Boot 4.0.3 + WebFlux                                |
| AI Framework        | Spring AI 2.0                                              |
| Vector Store        | PGVector (PostgreSQL 16 + pgvector extension)              |
| Conversation Memory | Redis 7.4 (RedisChatMemoryRepository)                      |
| Schema Management   | Flyway                                                     |
| LLM Providers       | Groq (primary), OpenAI (fallback), Ollama (local fallback) |
| Embeddings          | OpenAI `text-embedding-3-small`                            |
| Reranking           | Cohere `rerank-v4.0-pro`                                   |
| Evaluation          | RAGAS 0.2.x (Python FastAPI service)                       |
| Observability       | Spring Actuator, 28 endpoints                              |
| Build               | Gradle                                                     |

---

## API Reference

### Authentication
#### Admin Login
```bash
POST /api/v1/auth/login
{"email": "admin@springlens.com", "password": "Admin@123"}
→ {"token": "...", "expires_in": 3600}
```
#### User Login
```bash
POST /api/v1/auth/login
{"email": "user@springlens.com", "password": "User@123"}
→ {"token": "...", "expires_in": 3600}
```

### Document Ingestion
```bash
POST /api/v1/documents/ingest
Content-Type: multipart/form-data
file=@document.pdf
→ {"filename": "...", "chunks": 179, "status": "SUCCESS"}
```

### RAG Query 
### Streaming Query(stateful, Redis memory)
```bash
POST /api/v1/ai/chat/query
{"message": "...", "conversationId": "session-123", "memoryEnabled": true}
→ {"answer": "...", "sources": [...], "promptTokens": 1566,
   "completionTokens": 33, "latencyMs": 2452}
```

### Streaming Query(stateless)
```bash
GET /api/v1/ai/chat/stream?message=...&retrievalStrategy=hybrid
→ SSE stream: data: token\n\ndata: by\n\ndata: token\n\n
```

### Conversational Chat (stateful, Redis memory)
```bash
POST /api/v1/ai/chat
{"message": "...", "conversationId": "session-123", "memoryEnabled": true}
→ {"response": "...", "model": "...", "promptTokens": 3957}
```

### RAGAS Evaluation (Admin)
```bash
POST /api/v1/admin/evaluate
{"retrievalStrategy": "hybrid-rerank", "pairs": [...]}
→ {"scores": {"faithfulness": 0.87, "answer_relevancy": 0.87,
              "context_precision": 0.97, "context_recall": 0.89}}
```

### Quality Dashboard (Admin)
```bash
GET /api/v1/admin/quality?days=7
→ {"strategies": [{"retrievalStrategy": "hybrid-rerank",
                   "avgFaithfulness": 0.87, "runCount": 9, ...}]}
```

---

## Quick Start

### Prerequisites
- Java 21+
- Docker (for PostgreSQL + Redis)
- Groq API key (free tier works — [console.groq.com](https://console.groq.com))
- OpenAI API key (for embeddings + fallback LLM)
- Cohere API key (for reranking — [cohere.com](https://cohere.com))

### 1. Start Infrastructure
```bash
docker-compose up -d
# Starts PostgreSQL 16 with pgvector + Redis 7.4
```

### 2. Configure Environment
```bash
cp setenv.example.sh setenv.sh
# Edit setenv.sh — add your API keys
source setenv.sh
```

### 3. Run SpringLens
```bash
./startapp.sh
# Waits for: "Netty started on port 8087"
```

### 4. Ingest a Document
```bash
curl -X POST http://localhost:8087/api/v1/documents/ingest \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@your-document.pdf"
```

### 5. Ask a Question
```bash
curl -X POST http://localhost:8087/api/v1/ai/chat/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message": "Summarise the key compliance requirements", "retrievalStrategy": "hybrid-rerank"}'
```

---

## Project Structure

```
spring-lens/
├── src/main/java/com/ai/spring_lens/
│   ├── client/                  # LLM provider clients (Groq, OpenAI, Ollama)
│   ├── config/                  # All configuration — LLM, budget, RAGAS, retrieval
│   ├── controller/              # REST controllers — chat, documents, admin, auth
│   ├── model/                   # DTOs — QueryResponse, ChatResponse, RagasEvaluation
│   ├── repository/              # JDBC repositories — audit, budget, hybrid search, RAGAS
│   ├── security/                # JWT filter, TenantContext, authentication
│   └── service/                 # Core services
│       ├── SpringAiChatService  # RAG pipeline orchestrator
│       ├── ProviderRouterService# Multi-provider fallback chain
│       ├── BudgetEnforcementService # 6-check budget enforcement
│       ├── RagasEvaluationService   # RAGAS pipeline integration
│       ├── RagasRegressionAlertService # Historical regression detection
│       └── strategy/            # Retrieval strategy implementations
│           ├── VectorOnlyRetrievalStrategy
│           ├── HybridRetrievalStrategy
│           └── HybridWithRerankRetrievalStrategy
├── src/main/resources/
│   ├── application.yaml         # All configuration — externalised
│   └── db/migration/            # Flyway migrations (V1 baseline, V2 hybrid search)
├── springlens-ragas-evaluator/  # Python FastAPI evaluation service
│   ├── main.py
│   └── requirements.txt
├── setenv.example.sh            # Environment variable template
├── startapp.sh                  # Application startup script
└── docker-compose.yml           # PostgreSQL + Redis
```

---

## Roadmap

| Phase                          | Status      | Features                                                                                                                                     |
|--------------------------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Phase 1 — Foundation           | Complete    | LLM integration, PDF ingestion, basic RAG, streaming, Spring AI                                                                              |
| Phase 2 — Quality + Enterprise | Complete    | Hybrid search, Cohere reranking, RAGAS evaluation, JWT auth, multi-tenancy, budget enforcement, multi-provider fallback, conversation memory |
| Phase 3 — Agents               | In Progress | MCP protocol, autonomous agents, tool calling                                                                                                |
| Phase 4 — Multi-Agent          | Planned     | Orchestrator + Search + Analysis + Action agents                                                                                             |
| Phase 5 — MLOps                | Planned     | CI/CD quality gates, Kubernetes, fine-tuning experiment                                                                                      |

---

## Key Engineering Decisions

**Spring AI over LangChain4j** — Native Spring Boot auto-configuration, Advisors API for composable RAG pipelines, built-in Micrometer observability. Full write-up in [`framework-choice.md`](docs/framework-choice.md).

**WebFlux throughout** — Non-blocking reactive stack end-to-end. No `.block()` anywhere in the request path. Blocking operations (JDBC, vector search) isolated to `Schedulers.boundedElastic()`.

**Native WebClient for Ollama** — Spring AI's OpenAI abstraction leaks config when used with non-OpenAI providers. Ollama uses a native WebClient with explicit `stream: false` to prevent connection hang.

**Flyway owns schema** — `ddl-auto: none` from day one after migration. Hibernate validates, never creates. Lesson learned after a data loss incident during initial Flyway adoption.

**Retrieval Strategy as a Pattern** — `Map<String, RetrievalStrategy>` auto-injected by Spring. Adding a new strategy = one new `@Component`. Zero changes to service layer. Runtime switchable without restart.

---

## LEARNINGS.md

---

## Author

**Yogesh Kale** — Senior Java Backend Engineer transitioning to AI Platform Engineering.

6 years Java/Spring Boot → building Java-native AI infrastructure for enterprise teams.

---

## License  

MIT
This project is licensed under the MIT License © 2026 Yogesh Kale