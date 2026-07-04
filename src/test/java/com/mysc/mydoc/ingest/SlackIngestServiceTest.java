package com.mysc.mydoc.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.MemberRole;
import com.mysc.mydoc.domain.Space;
import com.mysc.mydoc.domain.SlackIngestLog;
import com.mysc.mydoc.repository.MemberRepository;
import com.mysc.mydoc.repository.SlackIngestLogRepository;
import com.mysc.mydoc.repository.SpaceRepository;
import com.mysc.mydoc.service.DocumentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

class SlackIngestServiceTest {

    @Test
    void onReactionAdded_whenIngestLogWriteFails_repliesFailureOnly() {
        RecordingSlackGateway slack = new RecordingSlackGateway(new SlackThread(
                "111.1",
                "https://slack.test/archives/C1/p1111",
                List.of(new SlackMessage("U1", "보람", "결정", "111.1"))
        ));
        ObjectProvider<SlackGateway> slackProvider = mock();
        ThreadSummaryPort summaries = messages -> new ThreadSummary(
                "저장 실패 문서",
                List.of(new ThreadSummary.Section("결정 사항", List.of("실패 답글만 남겨요.")))
        );
        DocumentService documents = mock();
        SpaceRepository spaces = mock();
        MemberRepository members = mock();
        SlackIngestLogRepository logs = mock();
        Space space = new Space("m4-space", "M4 Space");
        Member owner = new Member("owner@mysc.co.kr", "Owner", MemberRole.MEMBER);
        Document document = new Document(space, "저장 실패 문서", owner);

        when(slackProvider.getIfAvailable()).thenReturn(slack);
        when(spaces.findBySlug("m4-space")).thenReturn(Optional.of(space));
        when(members.findByEmail("owner@mysc.co.kr")).thenReturn(Optional.of(owner));
        when(logs.findByChannelIdAndThreadTs("C1", "111.1")).thenReturn(Optional.empty());
        when(documents.create(eq(space.getId()), eq("저장 실패 문서"), eq(owner.getId()))).thenReturn(document);
        when(logs.saveAndFlush(any(SlackIngestLog.class))).thenThrow(new DataIntegrityViolationException("thread_ts too long"));

        SlackIngestService service = new SlackIngestService(
                slackProvider,
                summaries,
                documents,
                spaces,
                members,
                logs,
                new ObjectMapper(),
                "m4-space",
                "http://mydoc.test"
        );

        assertThatCode(() -> service.onReactionAdded("C1", "111.1", "U1")).doesNotThrowAnyException();

        verify(logs).saveAndFlush(any(SlackIngestLog.class));
        assertThat(slack.replies).containsExactly(new Reply("C1", "111.1", "요약에 실패했어요. 잠시 후 다시 이모지를 달아주세요."));
    }

    private record Reply(String channelId, String threadTs, String text) {}

    private static class RecordingSlackGateway implements SlackGateway {
        private final SlackThread thread;
        private final List<Reply> replies = new ArrayList<>();

        private RecordingSlackGateway(SlackThread thread) {
            this.thread = thread;
        }

        @Override
        public SlackThread thread(String channelId, String messageTs) {
            return thread;
        }

        @Override
        public Optional<String> userEmail(String slackUserId) {
            return Optional.of("owner@mysc.co.kr");
        }

        @Override
        public void reply(String channelId, String threadTs, String text) {
            replies.add(new Reply(channelId, threadTs, text));
        }
    }
}
