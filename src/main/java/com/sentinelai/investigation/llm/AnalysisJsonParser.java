package com.sentinelai.investigation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelai.investigation.AnalysisResponseValidator;
import com.sentinelai.investigation.IncidentAnalysis;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses the model's reply into {@link IncidentAnalysis}.
 *
 * <p>Two realities of LLM APIs are handled here:
 * <ul>
 *   <li>Models wrap JSON in prose or markdown fences despite being told not to, so a
 *       fence-tolerant extraction runs before parsing.</li>
 *   <li>Field shapes vary, so parsing is lenient (unknown fields ignored, numbers
 *       coerced from strings) while {@link AnalysisResponseValidator} remains strict
 *       about content. Separating "could I read it" from "is it trustworthy" keeps
 *       each rule in one place.</li>
 * </ul>
 */
@Component
public class AnalysisJsonParser {

    private final ObjectMapper objectMapper;

    public AnalysisJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IncidentAnalysis parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("Model returned an empty response");
        }
        String json = extractJson(rawContent);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Model response was not valid JSON: " + ex.getMessage(), ex);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Model response was not a JSON object");
        }

        List<IncidentAnalysis.Hypothesis> hypotheses = new ArrayList<>();
        for (JsonNode node : root.path("hypotheses")) {
            hypotheses.add(new IncidentAnalysis.Hypothesis(
                    text(node, "cause"),
                    node.path("confidence").asDouble(Double.NaN),
                    textList(node, "evidence"),
                    textList(node, "nextChecks"),
                    textList(node, "evidenceEventIds")));
        }

        return new IncidentAnalysis(
                text(root, "summary"),
                hypotheses,
                textList(root, "missingEvidence"),
                textList(root, "caveats"));
    }

    /**
     * Finds the outermost JSON object, tolerating a ```json fence or surrounding
     * prose. Scanning for balanced braces rather than a regex matters: message
     * text inside the JSON commonly contains braces, which a greedy or lazy regex
     * would mangle.
     */
    static String extractJson(String raw) {
        String text = raw.strip();
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int afterFence = text.indexOf('\n', fenceStart);
            int fenceEnd = text.indexOf("```", fenceStart + 3);
            if (afterFence > 0 && fenceEnd > afterFence) {
                text = text.substring(afterFence + 1, fenceEnd).strip();
            }
        }

        // A top-level array is a response that did not follow the contract. Scanning
        // into it would silently return the first element and look like a successful
        // parse, so it is handed to the caller verbatim and rejected with a clear
        // message instead.
        if (text.startsWith("[")) {
            return text;
        }

        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return text.substring(start);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private List<String> textList(JsonNode node, String field) {
        JsonNode value = node.path(field);
        List<String> result = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(element -> {
                if (element.isTextual() && !element.asText().isBlank()) {
                    result.add(element.asText());
                } else if (element.isNumber() || element.isBoolean()) {
                    result.add(element.asText());
                }
            });
        } else if (value.isTextual() && !value.asText().isBlank()) {
            // Some models collapse a one-item array into a bare string.
            result.add(value.asText());
        }
        return result;
    }
}
