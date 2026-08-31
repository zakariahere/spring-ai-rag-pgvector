package lu.zakaria.myrag.ask;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The "GENERATE" half of RAG, wired once as a bean.
 *
 * A ChatClient (the fluent LLM API) with a QuestionAnswerAdvisor attached. On every
 * call the advisor: (1) runs a similarity search over the VectorStore, (2) injects
 * the hits into the prompt via the template below, (3) calls the chat model (DeepSeek).
 */
@Configuration
public class RagChatConfig {

    // The instruction wrapped around the retrieved chunks. The two placeholders are
    // REQUIRED by QuestionAnswerAdvisor: <query> (the question) and
    // <question_answer_context> (the retrieved chunks it splices in).
    private static final String RAG_TEMPLATE = """
            <query>

            Context information is below, between the dashed lines.

            ---------------------
            <question_answer_context>
            ---------------------

            Given the context information and no prior knowledge, answer the query.
            Rules:
            1. If the answer is not in the context, say you don't know — never invent one.
            2. Do not write "based on the context" or "the provided information".
            3. Be concise and factual.
            """;

    @Bean
    ChatClient ragChatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        // StringTemplate renderer using < > delimiters, matching the placeholders above.
        PromptTemplate template = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder()
                        .startDelimiterToken('<')
                        .endDelimiterToken('>')
                        .build())
                .template(RAG_TEMPLATE)
                .build();

        QuestionAnswerAdvisor qa = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().topK(4).build()) // how many chunks to ground on
                .promptTemplate(template)
                .build();

        // Attach the advisor as a default, so every prompt() call is RAG-grounded.
        return builder.defaultAdvisors(qa).build();
    }
}
