# spring-ai-rag-pgvector

> **The offline half of a RAG system** — ingest documents, split them into chunks,
> embed them locally with Ollama, and index the vectors in Postgres/pgvector.
> Built with Spring Boot + Spring AI. No LLM required (that's the *online* half — see the roadmap).

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.3-6DB33F)
![pgvector](https://img.shields.io/badge/pgvector-pg17-336791)
![Ollama](https://img.shields.io/badge/Ollama-nomic--embed--text-000000)
![Java](https://img.shields.io/badge/Java-21%2B-orange)

---

## What "offline RAG" means

Retrieval-Augmented Generation grounds a language model in *your* data. It splits
cleanly into two halves, and — crucially — you can build and test them separately:

| Half | When it runs | What it does | In this repo? |
|------|--------------|--------------|:---:|
| **Offline** (indexing) | once per document | read → chunk → embed → **index** | ✅ |
| **Online** (querying) | once per question | embed question → retrieve → prompt an LLM → answer | ⏭️ roadmap |

This repo is the **offline** half plus a bare `/search` endpoint, so you can *see*
exactly what retrieval returns before any generation is involved. Getting this half
right is where RAG quality is won or lost.

## The offline pipeline

```mermaid
flowchart LR
    A["📄 Document<br/>pdf · docx · md · html"] -->|Apache Tika| B["Raw text<br/>Document"]
    B -->|TokenTextSplitter<br/>256 tokens| C["Chunks"]
    C -->|OllamaEmbeddingModel<br/>nomic-embed-text| D["768-dim vectors"]
    D -->|PgVectorStore.add| E[("Postgres + pgvector<br/>vector_store + HNSW index")]
```

At **query** time the same embedding model turns your question into a vector, and
pgvector returns the nearest chunks via `ORDER BY embedding <=> :queryVector LIMIT k`.

## Stack

| Concern | Choice | Why |
|---------|--------|-----|
| Framework | Spring Boot 3.5 + Spring AI 1.0.3 | portable AI abstractions, the most-documented GA line |
| Vector store | pgvector (`pgvector/pgvector:pg17`) | it's just Postgres, with a `vector` column type + ANN index |
| Embeddings | Ollama `nomic-embed-text` (768-dim) | runs locally/on a homelab, free, deterministic |
| Doc parsing | Apache Tika | one reader for PDF / Word / HTML / Markdown / text |
| DB lifecycle | Spring Boot Docker Compose support | the app starts and wires the DB container itself |

---

## How to run it

**Prerequisites:** JDK 21+ · Docker · [Ollama](https://ollama.com) running locally
(or reachable on your network).

```bash
# 1. Make sure the embedding model is available (the app can also auto-pull it)
ollama pull nomic-embed-text

# 2. Start everything — Spring Boot brings up pgvector via Docker Compose
mvn spring-boot:run
```

That single command:
1. starts the `pgvector` container from `compose.yaml` and waits until it's healthy,
2. enables the `vector` extension (`docker/postgres/init/01-enable-pgvector.sql`),
3. creates the `vector_store` table + HNSW index (`initialize-schema: true`),
4. connects to Ollama and ensures `nomic-embed-text` is present,
5. serves on <http://localhost:8080>.

**Point at a remote Ollama** (e.g. a homelab box) without touching code:

```bash
OLLAMA_BASE_URL=http://<host>:11434 mvn spring-boot:run
```

## How to try the endpoints

Import-free options: use the included **Bruno** collection (`/bruno` → *Open Collection*),
or curl directly:

```bash
# Ingest the bundled primer doc (read → chunk → embed → index)
curl -s -X POST http://localhost:8080/rag/ingest/sample

# Ingest your own file — Tika handles PDF, docx, html, md, txt...
curl -s -F "file=@/path/to/your.pdf" http://localhost:8080/rag/ingest

# Search — the retrieval half, laid bare (top-K chunks + similarity scores)
curl -s "http://localhost:8080/rag/search?query=why%20chunk%20documents&topK=4"
```

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/rag/ingest/sample` | index the bundled `what-is-rag.md` |
| `POST` | `/rag/ingest` | index an uploaded file (multipart field `file`) |
| `GET`  | `/rag/search?query=&topK=` | return the most similar chunks |

Inspect what got stored:

```bash
docker exec myrag-pgvector-1 psql -U myrag -d myrag \
  -c "SELECT id, left(content,60), metadata FROM vector_store;"
```

---

## How it works — the ingestion pipeline

The whole offline half lives in [`IngestionService`](src/main/java/lu/zakaria/myrag/ingest/IngestionService.java).
It's Spring AI's **ETL pipeline**: `DocumentReader → DocumentTransformer → VectorStore`.

**1. Read (Extract).** `TikaDocumentReader` wraps Apache Tika, which detects the file
type from its bytes and extracts plain text — one API for every format. Output: a
`List<Document>` (usually one big `Document` per file).

**2. Chunk (Transform).** `TokenTextSplitter` breaks the text into passages measured
in *tokens* (via a real BPE tokenizer), not characters — because token budgets are what
embedders actually care about. Chunk size is the single biggest lever on retrieval
quality, so it's configurable (`myrag.chunk.size`, default 256).

**3. Embed + Index (Load).** `vectorStore.add(chunks)` does two things in one call:
it sends each chunk's text to the embedding model, then `INSERT`s the resulting
`(id, content, metadata, embedding)` rows into pgvector. You never write SQL or an
HTTP client — the `VectorStore` abstraction hides both.

> **Why a vector index and not `LIKE '%...%'`?** An embedding maps text to a point in
> 768-dimensional space where *distance ≈ difference in meaning*. So "how do I get my
> money back" lands near a passage about "refund policy" with zero shared keywords.
> The HNSW index keeps nearest-neighbour search fast as the table grows.

## How to tune it

The knobs worth turning first, in `src/main/resources/application.yml`:

```yaml
myrag:
  chunk:
    size: 256        # tokens per chunk — smaller = more focused hits, more rows
    min-chars: 150   # don't emit a chunk shorter than this
```

Retrieval quality depends far more on **chunk size** and the **embedding model** than
on anything downstream. Change `size`, re-ingest, re-search, and watch the scores move.

## How to avoid the common pitfalls

- **Match the vector dimensions to the model.** `nomic-embed-text` emits 768 numbers,
  so `spring.ai.vectorstore.pgvector.dimensions: 768`. A mismatch rejects every insert.
- **Opt into schema init.** `initialize-schema: true` is required in Spring AI 1.0+
  (it used to default on). Without it the `vector_store` table is never created.
- **Label the pgvector service for Boot.** The `pgvector/pgvector` image name doesn't
  contain "postgres", so Boot's Docker Compose support needs
  `org.springframework.boot.service-connection: postgres` in `compose.yaml` to wire it.
- **Re-ingesting appends.** There's no dedupe; `TRUNCATE vector_store;` for a clean slate.
- **Start Ollama before the app.** Ollama is a separate always-on server (like Postgres);
  Spring is only an HTTP client to it.

## What's next: the online half

To turn retrieved chunks into actual answers:
1. Add a chat model (`spring.ai.ollama.chat.model`, e.g. `qwen2.5-coder`).
2. Wire a `ChatClient` with a `QuestionAnswerAdvisor(vectorStore)` — Spring AI then does
   *retrieve → stuff-into-prompt → generate* on every call.
3. Add an `/ask?q=...` endpoint that returns a grounded, source-cited answer.

## Project layout

```
src/main/java/lu/zakaria/myrag/
  ├── MyragApplication.java          # Spring Boot entry point
  └── ingest/
      ├── IngestionService.java      # read → chunk → embed → index
      └── RagController.java         # /rag/ingest, /rag/ingest/sample, /rag/search
src/main/resources/
  ├── application.yml                # Ollama + pgvector config, chunk knobs
  └── docs/what-is-rag.md            # sample document to ingest
compose.yaml                         # pgvector service (+ Boot service-connection label)
docker/postgres/init/               # one-time SQL: CREATE EXTENSION vector
bruno/                               # ready-to-run Bruno API collection
```
