package com.sentinelai.investigation.llm;

/**
 * One request to a language model.
 *
 * <p>Deliberately minimal: a system prompt, a user prompt and an output cap. Any
 * provider abstraction that grew to expose tool calls, embeddings or multimodal
 * parts would be speculative — this project only ever asks for one JSON object.
 *
 * @param structuredInput optional already-parsed evidence. HTTP providers ignore it
 *                        and work from the prompts alone; the offline stub uses it
 *                        to reason over evidence instead of re-parsing rendered
 *                        text, which keeps the stub deterministic and testable.
 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        int maxOutputTokens,
        String requestTag,
        Object structuredInput
) {

    public static LlmRequest of(String systemPrompt, String userPrompt, int maxOutputTokens, String tag) {
        return new LlmRequest(systemPrompt, userPrompt, maxOutputTokens, tag, null);
    }

    public LlmRequest withStructuredInput(Object input) {
        return new LlmRequest(systemPrompt, userPrompt, maxOutputTokens, requestTag, input);
    }
}
