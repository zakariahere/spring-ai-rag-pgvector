package lu.zakaria.myrag.ingest;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The "INGEST" half of RAG: file -> read -> chunk -> embed -> store.
 *
 * Everything the embedding + persistence needs is hidden behind vectorStore.add():
 * Spring AI calls the embedding model for each chunk and upserts the rows into
 * pgvector for us.
 */
@Service
public class IngestionService {

    private final VectorStore vectorStore;

    // TokenTextSplitter counts *tokens* (not characters), matching how the
    // embedding model actually sees text. Chunk size is THE lever on RAG
    // quality, so we make it configurable (myrag.chunk.* in application.yml)
    // instead of hard-coding the 800-token default.
    private final TokenTextSplitter splitter;

    public IngestionService(VectorStore vectorStore,
                            @Value("${myrag.chunk.size:256}") int chunkSize,
                            @Value("${myrag.chunk.min-chars:150}") int minChunkSizeChars) {
        this.vectorStore = vectorStore;
        this.splitter = new TokenTextSplitter(
                chunkSize,           // target tokens per chunk
                minChunkSizeChars,   // don't emit a chunk shorter than this (chars)
                5,                   // drop chunks shorter than this to embed (chars)
                10_000,              // safety cap on number of chunks
                true);               // keep separators (newlines) in the text
    }

    public int ingest(Resource resource, String sourceName) {
        // 1. READ — Tika sniffs the file type and extracts plain text.
        //    One PDF usually comes back as a single big Document here.
        List<Document> docs = new TikaDocumentReader(resource).read();

        // 2. SPLIT — break each Document into token-sized chunks.
        List<Document> chunks = splitter.apply(docs);

        // 3. TAG — stamp every chunk with where it came from, so search results
        //    can tell you their origin (and so we could delete-by-source later).
        chunks.forEach(chunk -> chunk.getMetadata().put("source", sourceName));

        // 4. EMBED + STORE — this single call embeds every chunk and writes the
        //    (id, text, embedding vector, metadata) rows into pgvector.
        vectorStore.add(chunks);

        return chunks.size();
    }
}
