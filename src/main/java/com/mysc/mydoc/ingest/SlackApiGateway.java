package com.mysc.mydoc.ingest;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.model.Message;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnExpression("'${mydoc.slack.bot-token:}' != ''")
public class SlackApiGateway implements SlackGateway, SlackDmPort {
    private final MethodsClient slack;

    public SlackApiGateway(@Value("${mydoc.slack.bot-token}") String botToken) {
        this(Slack.getInstance().methods(botToken));
    }

    SlackApiGateway(MethodsClient slack) {
        this.slack = slack;
    }

    @Override
    public SlackThread thread(String channelId, String messageTs) {
        try {
            String threadTs = threadTs(channelId, messageTs);
            var replies = slack.conversationsReplies(request -> request.channel(channelId).ts(threadTs));
            if (!replies.isOk()) {
                throw new IllegalStateException("conversations.replies failed: " + replies.getError());
            }
            List<Message> messages = replies.getMessages();
            if (messages == null || messages.isEmpty()) {
                throw new IllegalStateException("Slack thread is empty");
            }
            String permalink = permalink(channelId, threadTs);
            return new SlackThread(threadTs, permalink, messages.stream().map(this::toSlackMessage).toList());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public Optional<String> userEmail(String slackUserId) {
        try {
            var response = slack.usersInfo(request -> request.user(slackUserId));
            if (!response.isOk() || response.getUser() == null || response.getUser().getProfile() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(response.getUser().getProfile().getEmail());
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    @Override
    public void reply(String channelId, String threadTs, String text) {
        try {
            var response = slack.chatPostMessage(request -> request.channel(channelId).threadTs(threadTs).text(text));
            if (!response.isOk()) {
                throw new IllegalStateException("chat.postMessage failed: " + response.getError());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void sendDm(String slackUserId, String text) {
        try {
            var response = slack.chatPostMessage(request -> request.channel(slackUserId).text(text));
            if (!response.isOk()) {
                throw new IllegalStateException("chat.postMessage failed: " + response.getError());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String threadTs(String channelId, String messageTs) throws Exception {
        var response = slack.conversationsHistory(request -> request
                .channel(channelId)
                .oldest(messageTs)
                .latest(messageTs)
                .inclusive(true)
                .limit(1)
        );
        if (!response.isOk()) {
            throw new IllegalStateException("conversations.history failed: " + response.getError());
        }
        List<Message> messages = response.getMessages();
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("Slack message is empty");
        }
        Message message = messages.get(0);
        return StringUtils.hasText(message.getThreadTs()) ? message.getThreadTs() : message.getTs();
    }

    private String permalink(String channelId, String threadTs) throws Exception {
        var response = slack.chatGetPermalink(request -> request.channel(channelId).messageTs(threadTs));
        if (!response.isOk()) {
            throw new IllegalStateException("chat.getPermalink failed: " + response.getError());
        }
        return response.getPermalink();
    }

    private SlackMessage toSlackMessage(Message message) {
        String userName = StringUtils.hasText(message.getUsername()) ? message.getUsername() : message.getUser();
        return new SlackMessage(message.getUser(), userName, message.getText(), message.getTs());
    }
}
