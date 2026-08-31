package lu.zakaria.myrag.ask;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
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
        // No chat key -> we do our OWN retrieval, since the advisor never runs. Retrieval
        // is the whole offline half; only generation needs the cloud model.
        if (!chatConfigured) {
            return Map.of("question", q,
                    "answer", "Chat is not configured — set DEEPSEEK_API_KEY in .env to get a generated answer. Retrieved context is below.",
                    "sources", toSources(vectorStore.similaritySearch(
                            SearchRequest.builder().query(q).topK(4).build())));
        }

        // ANSWER — the QuestionAnswerAdvisor (a default on this ChatClient) retrieves the
        // top chunks, splices them into the prompt, and calls DeepSeek. We capture the
        // full ChatResponse (not just .content()) so we can read the documents the advisor
        // ALREADY retrieved out of its metadata — no second search, and the sources are
        // guaranteed to be exactly what the model saw.
        ChatResponse response = chatClient.prompt()
                .user(q)
                .call()
                .chatResponse();

        String answer = response.getResult().getOutput().getText();

        Object retrieved = response.getMetadata()
                .getOrDefault(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Document> docs = (List<Document>) retrieved;

        return Map.of("question", q, "answer", answer, "sources", toSources(docs));
    }

    private static List<Map<String, Object>> toSources(List<Document> docs) {
        return docs.stream()
                .map(doc -> Map.<String, Object>of(
                        "score", doc.getScore(),
                        "source", doc.getMetadata().getOrDefault("source", "?"),
                        "text", doc.getText()))
                .toList();
    }
}
