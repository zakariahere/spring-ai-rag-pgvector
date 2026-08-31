-- Runs once, on first container creation (empty data volume), as a superuser.
-- Turns on the pgvector extension so Postgres understands the `vector` column type
-- and the `<=>` (cosine), `<->` (L2) and `<#>` (inner product) distance operators.
--
-- Spring AI's initialize-schema=true would also run this, but doing it here keeps
-- the extension setup explicit and independent of the app. (This is exactly what
-- the pg-init-sql skill automates.)
CREATE EXTENSION IF NOT EXISTS vector;
