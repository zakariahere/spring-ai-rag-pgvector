# Retrieval-Augmented Generation (RAG)

Retrieval-Augmented Generation is a technique for grounding a large language model
in your own data. Instead of relying only on what the model memorised during
training, RAG retrieves relevant passages from an external knowledge base at query
time and places them into the prompt. This reduces hallucination, keeps answers up
to date, and lets the model cite sources.

## Why RAG instead of fine-tuning?

Fine-tuning bakes knowledge into the model's weights, which is expensive, slow to
update, and hard to attribute. RAG keeps knowledge in an external store you can
edit at any time. Adding a new document is as cheap as embedding it and inserting a
row. Because the retrieved text is shown to the model verbatim, the model can quote
it and you can trace every answer back to a source.

## Chunking

Documents are split into smaller passages called chunks before they are stored.
Chunking matters because embedding models have a maximum input length, and because
a search should return a focused passage rather than an entire document. Chunks that
are too large make retrieval noisy; chunks that are too small lose their meaning.
A common strategy is to split on token counts with a small overlap so that a
sentence straddling a boundary still appears whole in at least one chunk.

## Embeddings

An embedding model converts a chunk of text into a vector of numbers, typically a
few hundred to a few thousand dimensions. Texts with similar meaning end up close
together in this vector space, even when they share no keywords. The phrase
"how do I get my money back" lands near a passage about "refund policy" because the
embedding captures meaning rather than surface words. The model nomic-embed-text
produces 768-dimensional vectors.

## Vector store and similarity search

A vector store persists chunks together with their embeddings and supports nearest
neighbour search. Postgres with the pgvector extension stores embeddings in a
`vector` column and compares them with distance operators: cosine distance, L2
distance, or inner product. An approximate index such as HNSW keeps search fast as
the collection grows to millions of rows. At query time the user's question is
embedded with the same model, and the store returns the top-K closest chunks.

## Putting it together

The full flow has two phases. Ingestion happens once per document: read, chunk,
embed, and store. Querying happens per question: embed the question, run a
similarity search to fetch the most relevant chunks, insert those chunks into a
prompt template as context, and ask the language model to answer using only that
context. The quality of a RAG system depends far more on retrieval quality than on
the language model, so chunking, the embedding model, and the number of retrieved
chunks are the parameters worth tuning first.
