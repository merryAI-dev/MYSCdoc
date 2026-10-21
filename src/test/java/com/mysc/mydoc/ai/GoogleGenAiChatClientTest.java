package com.mysc.mydoc.ai;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class GoogleGenAiChatClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void joinsAnswerPartsAndExcludesThoughts() throws Exception {
        var response = mapper.readValue("""
                {"candidates":[{"content":{"parts":[
                  {"text":"internal reasoning", "thought":true},
                  {"text":"첫 문장. "}, {"text":"둘째 문장."}]}}]}
                """, GoogleGenAiChatClient.GenerateContentResponse.class);
        assertThat(GoogleGenAiChatClient.extractText(response)).isEqualTo("첫 문장. 둘째 문장.");
    }

    @Test
    void missingOrBlockedAnswersAreUpstreamErrors() throws Exception {
        assertThatThrownBy(() -> GoogleGenAiChatClient.extractText(null))
                .isInstanceOf(RestClientException.class);
        for (String body : List.of("{}", "{\"candidates\":[]}",
                "{\"candidates\":[{}]}", "{\"candidates\":[{\"content\":{\"parts\":[]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" \"}]}}]}")) {
            var response = mapper.readValue(body, GoogleGenAiChatClient.GenerateContentResponse.class);
            assertThatThrownBy(() -> GoogleGenAiChatClient.extractText(response))
                    .isInstanceOf(RestClientException.class);
        }
    }
}
