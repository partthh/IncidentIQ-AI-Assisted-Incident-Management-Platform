package com.sentinelai.investigation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelai.config.InvestigationProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The real provider: any HTTP endpoint that speaks the OpenAI chat-completions API.
 *
 * <p>"OpenAI-compatible" rather than "OpenAI" is the point — vLLM, Ollama, LM Studio
 * and most gateways expose the same request and response shape, so switching
 * providers is a configuration change rather than a code change.
 *
 * <p>Three properties of this class are load-bearing and easy to get wrong:
 *
 * <ul>
 *   <li><b>Timeouts are explicit and finite.</b> {@code request-timeout} bounds the
 *       whole exchange, not just the socket. An unbounded provider call would pin a
 *       worker thread indefinitely, and the incident pipeline's independence from
 *       the AI module depends on that bound being real.</li>
 *   <li><b>Transport errors are classified, not string-matched.</b> A 429 and a 500
 *       are both retryable but mean different things; a 401 is neither, and retrying
 *       it only delays the FAILED status an operator is waiting for. The mapping lives
 *       in one switch so it can be reviewed as a whole.</li>
 *   <li><b>The response body is read with a hard cap.</b> A provider that returns
 *       megabytes of prose would otherwise be loaded fully into memory before the
 *       validator could reject it. The cap is applied while streaming, so an
 *       oversized body is abandoned rather than buffered.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "sentinel.investigation.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    /** Comfortably above any legitimate analysis, far below anything that hurts. */
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final InvestigationProperties properties;
    private final HttpClient httpClient;

    public OpenAiCompatibleLlmClient(ObjectMapper objectMapper, InvestigationProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        Duration timeout = properties.requestTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String modelName() {
        return properties.model();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (properties.openaiApiKey() == null || properties.openaiApiKey().isBlank()) {
            // Failing here rather than sending an unauthenticated request: the
            // provider's 401 is indistinguishable from a revoked key, and the
            // operator needs to be told the configuration is incomplete.
            throw new LlmException(LlmException.Kind.UNAUTHORIZED,
                    "sentinel.investigation.openai-api-key is not configured");
        }

        String body = renderRequest(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint())
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.openaiApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException ex) {
            throw new LlmException(LlmException.Kind.CONNECTION,
                    "Could not reach the model provider at " + properties.openaiBaseUrl(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.TIMEOUT, "Interrupted while waiting for the model provider");
        }

        try (InputStream stream = response.body()) {
            byte[] payload = readBounded(stream);
            if (response.statusCode() >= 400) {
                throw classify(response.statusCode(), new String(payload, java.nio.charset.StandardCharsets.UTF_8),
                        request.requestTag());
            }
            return interpret(payload);
        } catch (IOException ex) {
            throw new LlmException(LlmException.Kind.CONNECTION,
                    "Failed reading the model provider response", ex);
        }
    }

    private URI endpoint() {
        String base = properties.openaiBaseUrl() == null ? "" : properties.openaiBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/chat/completions");
    }

    /**
     * Reads at most {@link #MAX_RESPONSE_BYTES}, discarding the rest. Truncation is
     * detected rather than assumed: a truncated body will fail JSON parsing and be
     * recorded as a malformed response, which is the honest classification.
     */
    private byte[] readBounded(InputStream stream) throws IOException {
        byte[] buffer = new byte[MAX_RESPONSE_BYTES];
        int total = 0;
        while (total < buffer.length) {
            int read = stream.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        if (stream.read() >= 0) {
            log.warn("Model response exceeded {} bytes and was truncated", MAX_RESPONSE_BYTES);
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE,
                    "The model response exceeded " + MAX_RESPONSE_BYTES + " bytes");
        }
        return java.util.Arrays.copyOf(buffer, total);
    }

    private LlmResponse interpret(byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException ex) {
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE,
                    "The model provider returned a body that was not JSON", ex);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE,
                    "The model provider returned no choices");
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE,
                    "The model returned an empty completion");
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(
                content,
                root.path("model").asText(properties.model()),
                usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null,
                usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null);
    }

    /**
     * Maps an HTTP status to a retry decision. Kept explicit rather than derived from
     * a numeric range so that every provider behaviour is a visible, reviewable line.
     */
    private LlmException classify(int status, String body, String requestTag) {
        String detail = summarise(body);
        return switch (status) {
            case 400, 404, 422 -> new LlmException(LlmException.Kind.BAD_REQUEST,
                    "The model provider rejected the request (" + status + "): " + detail, status, null);
            case 401, 403 -> new LlmException(LlmException.Kind.UNAUTHORIZED,
                    "The model provider refused the credentials (" + status + ")", status, null);
            case 408 -> new LlmException(LlmException.Kind.TIMEOUT,
                    "The model provider timed out (" + status + ")", status, null);
            case 429 -> new LlmException(LlmException.Kind.RATE_LIMITED,
                    "The model provider is rate limiting this deployment (" + status + ")", status, null);
            default -> {
                if (status >= 500) {
                    yield new LlmException(LlmException.Kind.SERVER_ERROR,
                            "The model provider returned " + status + ": " + detail, status, null);
                }
                yield new LlmException(LlmException.Kind.MALFORMED_RESPONSE,
                        "Unexpected model provider status " + status + " for " + requestTag, status, null);
            }
        };
    }

    /** Provider error bodies can be large and can echo the prompt; keep only a prefix. */
    private String summarise(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    private String renderRequest(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));
        body.put("max_tokens", request.maxOutputTokens());
        // Near-zero temperature: the model is summarising evidence, and a reproducible
        // reading of the same incident is more useful than a creative one.
        body.put("temperature", 0.0d);
        body.put("response_format", Map.of("type", "json_object"));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not serialise the model request", ex);
        }
    }
}