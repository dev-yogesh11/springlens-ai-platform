# SpringLens

Java-native AI knowledge platform that turns any document corpus into clear, cited answers with autonomous agents.
This will be an AI platform that turns any document corpus into a queryable knowledge base with autonomous agents.

Built with Java 21 + Spring Boot 4.0 + Spring AI

## Vision
Ask questions → get cited answers → agents take autonomous actions
(create tickets, send alerts, draft reports)

## Status
Under active development — Phase 1 (Foundation)

## Tech Stack
- Java 21, Spring Boot 4 +
- Spring AI
- PGVector (vector store)
- Redis (conversation memory + semantic cache)
- Kafka (event-driven re-indexing)
- Neo4j (GraphRAG — optional layer)

## Project Structure
This project is built incrementally — dependencies are added only when needed.
