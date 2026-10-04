package com.sentinelai.investigation.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelai.investigation.IncidentAnalysis;
import org.junit.jupiter.api.Test;

/**
 * Parsing is the tolerant half of the AI pipeline: it decides whether a reply can
 * be <em>read</em>. Trustworthiness is decided afterwards by
 * {@code AnalysisResponseValidator}. These tests cover the shapes real providers
 * actually return — fenced output, prose around JSON, single-element arrays collapsed
 * to a bare string — because those are the failures that otherwise turn a good model
 * answer into a {@code REJECTED} row for no good reason.
 */
class AnalysisJsonParserTest {

    private final AnalysisJsonParser parser = new AnalysisJsonParser(new ObjectMapper());

    @Test
    void parsesTheDocumentedContract() {
        IncidentAnalysis analysis = parser.parse("""
                {
                  "summary": "Latency spike caused by pool exhaustion.",
                  "hypotheses": [
                    {
                      "cause": "Connection pool saturation",
                      "confidence": 0.82,
                      "evidence": ["poolUtilization=0.97"],
                      "nextChecks": ["Inspect slow queries"],
                      "evidenceEventIds": ["3f1b2c4d-0000-4000-8000-0000000000aa"]
                    }
                  ],
                  "missingEvidence": ["No deploy markers in window"],
                  "caveats": ["Single sample"]
                }
                """);

        assertThat(analysis.summary()).isEqualTo("Latency spike caused by pool exhaustion.");
        assertThat(analysis.hypotheses()).hasSize(1);

        IncidentAnalysis.Hypothesis hypothesis = analysis.hypotheses().get(0);
        assertThat(hypothesis.cause()).isEqualTo("Connection pool saturation");
        assertThat(hypothesis.confidence()).isEqualTo(0.82d);
        assertThat(hypothesis.evidence()).containsExactly("poolUtilization=0.97");
        assertThat(hypothesis.nextChecks()).containsExactly("Inspect slow queries");
        assertThat(hypothesis.evidenceEventIds())
                .containsExactly("3f1b2c4d-0000-4000-8000-0000000000aa");
        assertThat(analysis.missingEvidence()).containsExactly("No deploy markers in window");
        assertThat(analysis.caveats()).containsExactly("Single sample");
    }

    @Test
    void extractsJsonFromAMarkdownFence() {
        String raw = """
                Here is my analysis:
                ```json
                {"summary":"ok","hypotheses":[{"cause":"c","confidence":0.5,
                 "evidence":["e"],"nextChecks":["c"]}]}
                ```
                Let me know if you need more.
                """;

        assertThat(parser.parse(raw).summary()).isEqualTo("ok");
    }

    @Test
    void extractsJsonSurroundedByProse() {
        String raw = "Sure! {\"summary\":\"ok\",\"hypotheses\":[]} Hope that helps.";

        assertThat(parser.parse(raw).summary()).isEqualTo("ok");
    }

    @Test
    void handlesBracesInsideStringValues() {
        // The reason extraction scans balanced braces instead of using a regex: log
        // text quoted inside the JSON is full of braces, and a lazy match would stop
        // at the first one and produce invalid JSON.
        String raw = """
                {"summary":"stack frame at Foo.bar() threw {\\"detail\\": \\"boom\\"}",
                 "hypotheses":[{"cause":"c","confidence":0.4,"evidence":["e"],"nextChecks":["n"]}]}
                """;

        IncidentAnalysis analysis = parser.parse(raw);

        assertThat(analysis.summary()).contains("Foo.bar()");
        assertThat(analysis.hypotheses()).hasSize(1);
    }

    @Test
    void handlesNestedObjectsInEvidence() {
        String raw = """
                {"summary":"ok","hypotheses":[{"cause":"c","confidence":0.4,
                 "evidence":[{"metric":"cpu","value":0.9}],"nextChecks":["n"]}]}
                """;

        assertThat(parser.parse(raw).hypotheses().get(0).cause()).isEqualTo("c");
    }

    @Test
    void coercesStringConfidenceBecauseModelsSometimesQuoteNumbers() {
        String raw = """
                {"summary":"ok","hypotheses":[{"cause":"c","confidence":"0.77",
                 "evidence":["e"],"nextChecks":["n"]}]}
                """;

        assertThat(parser.parse(raw).hypotheses().get(0).confidence()).isEqualTo(0.77d);
    }

    @Test
    void collapsesSingleElementArraysToBareStrings() {
        String raw = """
                {"summary":"ok","hypotheses":[{"cause":"c","confidence":0.4,
                 "evidence":"one line of evidence","nextChecks":"do a thing"}]}
                """;

        IncidentAnalysis.Hypothesis hypothesis = parser.parse(raw).hypotheses().get(0);

        assertThat(hypothesis.evidence()).containsExactly("one line of evidence");
        assertThat(hypothesis.nextChecks()).containsExactly("do a thing");
    }

    @Test
    void ignoresUnknownFieldsRatherThanFailing() {
        // Providers add fields without warning. A new key must not turn a usable
        // answer into a rejection.
        String raw = """
                {"summary":"ok","reasoningTokens":412,"model":"gpt-x",
                 "hypotheses":[{"cause":"c","confidence":0.4,"evidence":["e"],
                 "nextChecks":["n"],"rank":1}]}
                """;

        assertThat(parser.parse(raw).hypotheses().get(0).cause()).isEqualTo("c");
    }

    @Test
    void missingConfidenceBecomesNaNSoTheValidatorCanRejectIt() {
        // NaN is the sentinel on purpose: the parser stays tolerant, and the strict
        // rule ("confidence must be 0..1") lives in exactly one place.
        String raw = """
                {"summary":"ok","hypotheses":[{"cause":"c","evidence":["e"],"nextChecks":["n"]}]}
                """;

        assertThat(parser.parse(raw).hypotheses().get(0).confidence()).isNaN();
    }

    @Test
    void nonStringArrayElementsAreCoercedToText() {
        String raw = """
                {"summary":"ok","hypotheses":[{"cause":"c","confidence":0.4,
                 "evidence":[1500,"ms"],"nextChecks":["n"]}]}
                """;

        assertThat(parser.parse(raw).hypotheses().get(0).evidence())
                .containsExactly("1500", "ms");
    }

    @Test
    void blankArrayElementsAreDropped() {
        String raw = """
                {"summary":"ok","hypotheses":[{"cause":"c","confidence":0.4,
                 "evidence":["real","","   "],"nextChecks":["n"]}]}
                """;

        assertThat(parser.parse(raw).hypotheses().get(0).evidence()).containsExactly("real");
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{summary: ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsAJsonArrayAtTheTopLevel() {
        // An array is valid JSON but not the contract; accepting it would silently
        // produce an analysis with no hypotheses.
        assertThatThrownBy(() -> parser.parse("[{\"summary\":\"ok\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    void proseWithNoJsonIsRejectedWithAUsefulMessage() {
        assertThatThrownBy(() -> parser.parse("I am unable to analyse this incident."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hypothesesDefaultToEmptyRatherThanNull() {
        IncidentAnalysis analysis = parser.parse("{\"summary\":\"ok\"}");

        assertThat(analysis.hypotheses()).isNotNull().isEmpty();
        assertThat(analysis.missingEvidence()).isNotNull().isEmpty();
        assertThat(analysis.caveats()).isNotNull().isEmpty();
    }

    @Test
    void extractionStopsAtTheMatchingBraceNotTheLastOne() {
        // Regression guard for the balanced-brace scan: a regex would swallow the
        // trailing prose and produce unparseable output.
        String raw = "{\"a\":{\"b\":{\"c\":1}},\"d\":\"after\"}";
        assertThat(AnalysisJsonParser.extractJson(raw))
                .isEqualTo("{\"a\":{\"b\":{\"c\":1}},\"d\":\"after\"}");
    }
}