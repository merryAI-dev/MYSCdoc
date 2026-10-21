package com.mysc.mydoc.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;

class AnthropicChatClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void ignoresThinkingAndRejectsTruncation() throws Exception {
        var r = mapper.readTree("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"private\"},{\"type\":\"text\",\"text\":\"answer\"}]}");
        assertThat(AnthropicChatClient.extract(r, false)).isEqualTo("answer");
        ((com.fasterxml.jackson.databind.node.ObjectNode)r).put("stop_reason", "max_tokens");
        assertThatThrownBy(() -> AnthropicChatClient.extract(r, false)).isInstanceOf(RestClientException.class);
    }
    @Test void extractsToolInputAndRejectsTextOnly() throws Exception {
        var r = mapper.readTree("{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_result\",\"input\":{\"ok\":true}}]}");
        assertThat(mapper.readTree(AnthropicChatClient.extract(r, true)).path("ok").asBoolean()).isTrue();
        assertThatThrownBy(() -> AnthropicChatClient.extract(r, false)).isInstanceOf(RestClientException.class);
        assertThatThrownBy(() -> AnthropicChatClient.extract(mapper.readTree("{\"stop_reason\":\"end_turn\",\"content\":[]}"), false)).isInstanceOf(RestClientException.class);
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CLAUDE_LIVE_TEST", matches = "true")
    void liveTextAndStructuredOutput() throws Exception {
        var client = new AnthropicChatClient(RestClient.builder(), System.getenv("ANTHROPIC_API_KEY"), "claude-haiku-5-5", 1024);
        assertThat(client.answer("Return exactly API_OK.", "Connectivity test")).isEqualTo("API_OK");
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"ok\"]}},\"required\":[\"status\"],\"additionalProperties\":false}");
        var result = mapper.readTree(client.summarizeStructured("Return status ok using the supplied tool.", "Connectivity test", schema));
        assertThat(result.path("status").asText()).isEqualTo("ok");
    }
}
