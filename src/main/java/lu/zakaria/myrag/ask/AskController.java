package lu.zakaria.myrag.ask;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The full RAG loop in one endpoint: ask a question, get a grounded answer plus the
 * sources it was grounded on.
 */
@RestController
@RequestMapping("/rag")
public class AskController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final boolean chatConfigured;

    public AskController(ChatClient chatClient,
                         VectorStore vectorStore,
                         @Value("${spring.ai.deepseek.api-key:}") String deepSeekKey) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        // The app boots with a dummy key so Phase 1 stays usable; detect it here so
        // /ask degrades gracefully instead of throwing a 401 from DeepSeek.
        this.chatConfigured = !deepSeekKey.isBlank() && !"not-configured".equals(deepSeekKey);
    }

    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam String q) {
        // SOURCES — the chunks retrieval stands on, surfaced either way so the answer
        // is auditable (and so /search-style output is available even without a chat key).
        List<Map<String, Object>> sources = vectorStore.similaritySearch(
                        SearchRequest.builder().query(q).topK(4).build())
                .stream()
                .map(doc -> Map.<String, Object>of(
                        "score", doc.getScore(),
                        "source", doc.getMetadata().getOrDefault("source", "?"),
                        "text", doc.getText()))
                .toList();

        // No chat key -> return the retrieved context without generating. Retrieval is
        // the whole offline half; generation is the part that needs the cloud model.
        if (!chatConfigured) {
            return Map.of("question", q,
                    "answer", "Chat is not configured — set DEEPSEEK_API_KEY in .env to get a generated answer. Retrieved context is below.",
                    "sources", sources);
        }

        // ANSWER — the QuestionAnswerAdvisor (a default on this ChatClient) retrieves the
        // top chunks, splices them into the prompt, and calls DeepSeek. All of
        // retrieve -> augment -> generate is hidden behind this one fluent call.
        String answer = chatClient.prompt()
                .user(q)
                .call()
                .content();

        return Map.of("question", q, "answer", answer, "sources", sources);
    }
}
