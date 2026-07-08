-- 아카이빙 채널 옵트인: 봇이 초대돼 있어도 여기서 켠 채널만 수집한다 (RISKS C-1 동의 원칙 강화)
CREATE TABLE slack_channel_config (
    channel_id      varchar(50)  PRIMARY KEY,
    channel_name    varchar(200) NOT NULL,
    archive_enabled boolean      NOT NULL DEFAULT false,
    updated_at      timestamptz  NOT NULL
);

-- 의사결정 추출 잡의 미세조정 값 (단일 행)
CREATE TABLE knowledge_setting (
    id            int PRIMARY KEY CHECK (id = 1),
    quiet_minutes int NOT NULL,
    min_messages  int NOT NULL,
    updated_at    timestamptz NOT NULL
);
INSERT INTO knowledge_setting (id, quiet_minutes, min_messages, updated_at) VALUES (1, 30, 3, now());
