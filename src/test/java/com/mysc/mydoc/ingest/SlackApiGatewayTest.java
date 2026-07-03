package com.mysc.mydoc.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slack.api.RequestConfigurator;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatGetPermalinkRequest;
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest;
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest;
import com.slack.api.methods.response.chat.ChatGetPermalinkResponse;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlackApiGatewayTest {

    @Test
    @SuppressWarnings("unchecked")
    void thread_withReplyTimestamp_fetchesParentThread() throws Exception {
        MethodsClient slack = mock(MethodsClient.class);
        when(slack.conversationsHistory(anyHistory())).thenAnswer(invocation -> {
            var request = ((RequestConfigurator<ConversationsHistoryRequest.ConversationsHistoryRequestBuilder>) invocation.getArgument(0))
                    .configure(ConversationsHistoryRequest.builder())
                    .build();
            assertThat(request.getChannel()).isEqualTo("C1");
            assertThat(request.getOldest()).isEqualTo("111.2");
            assertThat(request.getLatest()).isEqualTo("111.2");
            assertThat(request.isInclusive()).isTrue();
            assertThat(request.getLimit()).isEqualTo(1);
            return history(reply("111.2", "111.1"));
        });
        when(slack.conversationsReplies(anyReplies())).thenAnswer(invocation -> {
            var request = ((RequestConfigurator<ConversationsRepliesRequest.ConversationsRepliesRequestBuilder>) invocation.getArgument(0))
                    .configure(ConversationsRepliesRequest.builder())
                    .build();
            assertThat(request.getChannel()).isEqualTo("C1");
            assertThat(request.getTs()).isEqualTo("111.1");
            return replies(message("111.1"), reply("111.2", "111.1"));
        });
        when(slack.chatGetPermalink(anyPermalink())).thenReturn(permalink("https://slack.test/archives/C1/p1111"));

        SlackThread thread = new SlackApiGateway(slack).thread("C1", "111.2");

        assertThat(thread.threadTs()).isEqualTo("111.1");
        assertThat(thread.permalink()).isEqualTo("https://slack.test/archives/C1/p1111");
        assertThat(thread.messages()).extracting(SlackMessage::ts).containsExactly("111.1", "111.2");
    }

    @SuppressWarnings("unchecked")
    private RequestConfigurator<ConversationsHistoryRequest.ConversationsHistoryRequestBuilder> anyHistory() {
        return any(RequestConfigurator.class);
    }

    @SuppressWarnings("unchecked")
    private RequestConfigurator<ConversationsRepliesRequest.ConversationsRepliesRequestBuilder> anyReplies() {
        return any(RequestConfigurator.class);
    }

    @SuppressWarnings("unchecked")
    private RequestConfigurator<ChatGetPermalinkRequest.ChatGetPermalinkRequestBuilder> anyPermalink() {
        return any(RequestConfigurator.class);
    }

    private ConversationsHistoryResponse history(Message message) {
        ConversationsHistoryResponse response = new ConversationsHistoryResponse();
        response.setOk(true);
        response.setMessages(List.of(message));
        return response;
    }

    private ConversationsRepliesResponse replies(Message... messages) {
        ConversationsRepliesResponse response = new ConversationsRepliesResponse();
        response.setOk(true);
        response.setMessages(List.of(messages));
        return response;
    }

    private ChatGetPermalinkResponse permalink(String url) {
        ChatGetPermalinkResponse response = new ChatGetPermalinkResponse();
        response.setOk(true);
        response.setPermalink(url);
        return response;
    }

    private Message message(String ts) {
        Message message = new Message();
        message.setTs(ts);
        message.setUser("U1");
        message.setText("root");
        return message;
    }

    private Message reply(String ts, String threadTs) {
        Message message = message(ts);
        message.setThreadTs(threadTs);
        return message;
    }
}
