package com.mysc.mydoc.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 원본이 알려주는 '사건 시각'을 파싱한다 — 회의일·메시지 시각. LLM 불필요.
 * 소스마다 형식이 달라 한 곳에 모아 배선 지점을 단순화한다.
 */
public final class EventDates {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // Drive 회의록 제목 규칙: "YYYYMMDD [회의명] ..."
    private static final Pattern DRIVE_TITLE_DATE = Pattern.compile("^(20\\d{2})(\\d{2})(\\d{2})");

    private EventDates() {}

    /** Drive 회의록 제목의 앞 YYYYMMDD → 그날 00:00 KST. 없으면 empty. */
    public static Optional<Instant> fromDriveTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return Optional.empty();
        }
        Matcher m = DRIVE_TITLE_DATE.matcher(title.strip());
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            return Optional.of(date.atStartOfDay(KST).toInstant());
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** Slack ts는 epoch 초(문자열, "1783081487.013279"). 2020년 이후만 유효로 본다. */
    public static Optional<Instant> fromEpochSeconds(String ts) {
        if (!StringUtils.hasText(ts) || !ts.matches("\\d+\\.?\\d*")) {
            return Optional.empty();
        }
        try {
            Instant instant = Instant.ofEpochMilli((long) (Double.parseDouble(ts) * 1000));
            return instant.isBefore(Instant.parse("2020-01-01T00:00:00Z")) ? Optional.empty() : Optional.of(instant);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /**
     * ISO 계열 날짜/시각 문자열 — Tiro recordingStartAt, Meetily created_at 등.
     * "2026-06-14T10:00:00Z", "2026-06-14T10:00:00", "2026-06-14 10:00:00", "2026-06-14"를 받는다.
     */
    public static Optional<Instant> fromIsoish(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        String value = text.strip();
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant()); // 오프셋/Z 포함
        } catch (RuntimeException ignore) {
            // 오프셋 없는 형식들 — KST로 해석
        }
        String normalized = value.replace(' ', 'T');
        try {
            return Optional.of(LocalDateTime.parse(normalized).atZone(KST).toInstant());
        } catch (RuntimeException ignore) {
            // 날짜만
        }
        try {
            return Optional.of(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(KST).toInstant());
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
