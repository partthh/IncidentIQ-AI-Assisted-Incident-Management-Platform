package com.sentinelai.investigation.llm;

/**
 * A raw model reply.
 *
 * <p>{@code rawContent} is kept verbatim alongside the provider metadata so a
 * rejected response can still be inspected — a validator failure with no record of
 * what actually arrived is the hardest kind of bug to diagnose.
 */
public record LlmResponse(
        String rawContent,
        String model,
        Integer promptTokens,
        Integer completionTokens
) {
}
