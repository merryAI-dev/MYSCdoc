package com.mysc.mydoc.ingest;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.model.Message;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnExpression("'${mydoc.slack.bot-token:}' != ''")
public class SlackApiGateway implements SlackGateway, SlackDmPort {
    /** conversations.history 한 페이지 크기 — Slack 권장 상한. 그 이상은 커서로 이어 받는다. */
    private static final int SLACK_PAGE_SIZE = 200;

    private final MethodsClient slack;

    // 생성자가 2개(테스트용 포함)라 Spring이 고를 수 있게 명시한다. 실토큰으로 기동해야만 드러나는 지점.
    @Autowired
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

    @Override
    public List<SlackChannel> memberChannels() {
        try {
            List<SlackChannel> channels = new java.util.ArrayList<>();
            String cursor = null;
            do {
                final String pageCursor = cursor;
                var response = slack.conversationsList(request -> request
                        .types(List.of(com.slack.api.model.ConversationType.PUBLIC_CHANNEL,
                                com.slack.api.model.ConversationType.PRIVATE_CHANNEL))
                        .limit(200)
                        .cursor(pageCursor));
                if (!response.isOk()) {
                    throw new IllegalStateException("conversations.list failed: " + response.getError());
                }
                for (var conversation : response.getChannels()) {
                    if (Boolean.TRUE.equals(conversation.isMember())) {
                        channels.add(new SlackChannel(conversation.getId(), conversation.getName(), conversation.isPrivate()));
                    }
                }
                cursor = response.getResponseMetadata() == null ? null : response.getResponseMetadata().getNextCursor();
            } while (StringUtils.hasText(cursor));
            return channels;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public List<ArchivableMessage> channelHistory(String channelId, int limit) {
        try {
            List<ArchivableMessage> archivable = new java.util.ArrayList<>();
            String cursor = null;
            int fetchedParents = 0;
            // 커서 페이지네이션 — 예전엔 단발 호출이라 채널당 최근 N건이 상한이었다.
            // Slack 권장 페이지 크기는 200이므로 그 단위로 limit까지 거슬러 올라간다.
            do {
                final String pageCursor = cursor;
                final int pageSize = Math.min(SLACK_PAGE_SIZE, limit - fetchedParents);
                var history = slack.conversationsHistory(request -> {
                    request.channel(channelId).limit(pageSize);
                    if (pageCursor != null) {
                        request.cursor(pageCursor);
                    }
                    return request;
                });
                if (!history.isOk()) {
                    throw new IllegalStateException("conversations.history failed: " + history.getError());
                }
                List<Message> messages = nonNull(history.getMessages());
                for (Message parent : messages) {
                    fetchedParents++;
                    addIfArchivable(archivable, parent, parent.getTs());
                    // 답글이 있는 스레드는 펼쳐서 답글까지 아카이브한다.
                    if (parent.getReplyCount() != null && parent.getReplyCount() > 0) {
                        var replies = slack.conversationsReplies(request -> request.channel(channelId).ts(parent.getTs()));
                        if (replies.isOk()) {
                            for (Message reply : nonNull(replies.getMessages())) {
                                if (!reply.getTs().equals(parent.getTs())) { // 루트는 위에서 이미 넣음
                                    addIfArchivable(archivable, reply, parent.getTs());
                                }
                            }
                        }
                    }
                }
                cursor = messages.isEmpty() || history.getResponseMetadata() == null
                        ? null : history.getResponseMetadata().getNextCursor();
                if (cursor != null && cursor.isBlank()) {
                    cursor = null;
                }
            } while (cursor != null && fetchedParents < limit);
            return archivable;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    // 봇/시스템 메시지(subtype 있음)와 빈 본문은 아카이브에서 제외한다.
    private void addIfArchivable(List<ArchivableMessage> out, Message message, String threadTs) {
        if (message.getBotId() != null || StringUtils.hasText(message.getSubtype())) {
            return;
        }
        if (!StringUtils.hasText(message.getUser()) || !StringUtils.hasText(message.getText())) {
            return;
        }
        out.add(new ArchivableMessage(message.getTs(), threadTs, message.getUser(), message.getText()));
    }

    private static List<Message> nonNull(List<Message> messages) {
        return messages == null ? List.of() : messages;
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
