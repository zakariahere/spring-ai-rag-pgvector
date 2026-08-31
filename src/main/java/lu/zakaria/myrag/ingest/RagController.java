package lu.zakaria.myrag.ingest;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final IngestionService ingestion;
    private final VectorStore vectorStore;
    private final Resource sampleDoc;

    public RagController(IngestionService ingestion,
                         VectorStore vectorStore,
                         @Value("classpath:docs/what-is-rag.md") Resource sampleDoc) {
        this.ingestion = ingestion;
        this.vectorStore = vectorStore;
        this.sampleDoc = sampleDoc;
    }

    /** One-click test: ingest the bundled primer doc. */
    @PostMapping("/ingest/sample")
    public Map<String, Object> ingestSample() {
        int chunks = ingestion.ingest(sampleDoc, "what-is-rag.md");
        return Map.of("source", "what-is-rag.md", "ingestedChunks", chunks);
    }

    /** Ingest any uploaded file: PDF, docx, html, md, txt... Tika figures out the type. */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file) throws IOException {
        // Wrap the upload as a Resource. Override getFilename so Tika/metadata
        // still know the original name.
        Resource resource = new InputStreamResource(file.getInputStream()) {
            @Override public String getFilename() { return file.getOriginalFilename(); }
            @Override public long contentLength() { return file.getSize(); }
        };
        int chunks = ingestion.ingest(resource, file.getOriginalFilename());
        return Map.of("source", file.getOriginalFilename(), "ingestedChunks", chunks);
    }

    /**
     * The RETRIEVAL half of RAG, laid bare. No LLM yet — this returns the raw
     * chunks pgvector thinks are most similar to your query, plus their scores,
     * so you can SEE what the model would be handed in Phase 2.
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String query,
                                             @RequestParam(defaultValue = "4") int topK) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .build());

        return hits.stream()
                .map(doc -> Map.<String, Object>of(
                        "score", doc.getScore(),                              // 1.0 = perfect match
                        "source", doc.getMetadata().getOrDefault("source", "?"),
                        "text", doc.getText()))
                .toList();
    }
}
