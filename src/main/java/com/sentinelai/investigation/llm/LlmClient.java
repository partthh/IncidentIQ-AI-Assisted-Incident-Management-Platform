package com.sentinelai.investigation.llm;

/**
 * The seam between the incident pipeline and any language model.
 *
 * <p>Everything above this interface — context assembly, retries, validation,
 * persistence, broadcasting — is provider-agnostic and testable with a fake. That
 * is what allows the failure and injection tests to run deterministically and
 * offline, and it is why swapping providers is a configuration change.
 */
public interface LlmClient {

    /**
     * @throws LlmException on any provider failure; the caller decides on retries
     */
    LlmResponse complete(LlmRequest request);

    /** Model identifier recorded on every analysis for reproducibility. */
    String modelName();
}
