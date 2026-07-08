package com.mysc.mydoc.ingest.archive;

import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.ingest.SlackGateway;
import com.mysc.mydoc.ingest.SlackGateway.ArchivableMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 채널의 기존 논의를 시스템이 스스로 읽어와 아카이브에 넣는다(백필, 읽기 전용).
 * Socket Mode는 새 메시지만 잡으므로, 이미 쌓여 있던 논의를 지식으로 만들려면 이 백필이 필요하다.
 * 실제 저장은 {@link SlackArchiveService}를 재사용해 옵트인 확인·중복 제거·봇 필터를 그대로 적용받는다.
 */
@Service
public class SlackBackfillService {
    private static final Logger log = LoggerFactory.getLogger(SlackBackfillService.class);

    private final ObjectProvider<SlackGateway> slack;
    private final SlackArchiveService archive;

    public SlackBackfillService(ObjectProvider<SlackGateway> slack, SlackArchiveService archive) {
        this.slack = slack;
        this.archive = archive;
    }

    /**
     * 채널 최근 {@code limit}개 메시지(+스레드 답글)를 아카이브에 넣는다.
     * archive()가 옵트인 미설정·중복이면 조용히 건너뛰므로, 반환값은 실제 저장수가 아니라 시도 건수다.
     * (실제로 몇 스레드가 문서화됐는지는 이 뒤에 도는 sync가 판단한다.)
     */
    public int backfill(String channelId, int limit) {
        SlackGateway gateway = slack.getIfAvailable();
        if (gateway == null) {
            throw new ValidationException("Slack gateway is not configured");
        }
        int attempted = 0;
        for (ArchivableMessage message : gateway.channelHistory(channelId, limit)) {
            archive.archive(channelId, message.ts(), message.threadTs(), message.userId(), message.text());
            attempted++;
        }
        log.info("Slack backfill: channel={} 메시지 {}건 아카이브 시도", channelId, attempted);
        return attempted;
    }
}
