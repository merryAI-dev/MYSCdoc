package com.mysc.mydoc.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class EventDatesTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    void parsesDriveTitleDate() {
        Instant ev = EventDates.fromDriveTitle("20260709 [프로그램 기획 회의] 중앙대_권혁준").orElseThrow();
        ZonedDateTime kst = ev.atZone(KST);
        assertThat(kst.getYear()).isEqualTo(2026);
        assertThat(kst.getMonthValue()).isEqualTo(7);
        assertThat(kst.getDayOfMonth()).isEqualTo(9);
        assertThat(kst.getHour()).isZero(); // 그날 00:00 KST
    }

    @Test
    void rejectsTitleWithoutLeadingDate() {
        assertThat(EventDates.fromDriveTitle("MYSC 회의록")).isEmpty();
        assertThat(EventDates.fromDriveTitle("회의 20260709")).isEmpty(); // 앞이 아니면 무시
        assertThat(EventDates.fromDriveTitle("20261345 잘못된날짜")).isEmpty(); // 13월 45일
    }

    @Test
    void parsesSlackEpochSeconds() {
        // 1783081487 = 2026-07-01 근처
        Instant ev = EventDates.fromEpochSeconds("1783081487.013279").orElseThrow();
        assertThat(ev.atZone(KST).getYear()).isEqualTo(2026);
    }

    @Test
    void rejectsPre2020AndNonNumericEpoch() {
        assertThat(EventDates.fromEpochSeconds("1.1")).isEmpty();       // 1970 테스트 행
        assertThat(EventDates.fromEpochSeconds("")).isEmpty();
        assertThat(EventDates.fromEpochSeconds("abc")).isEmpty();
    }

    @Test
    void parsesIsoishVariants() {
        assertThat(EventDates.fromIsoish("2026-06-14T10:00:00Z")).isPresent();
        assertThat(EventDates.fromIsoish("2026-06-14 10:00:00")).isPresent(); // Meetily 형식
        assertThat(EventDates.fromIsoish("2026-06-14")).isPresent();          // 날짜만
        assertThat(EventDates.fromIsoish("")).isEmpty();
        assertThat(EventDates.fromIsoish("어제")).isEmpty();
    }
}
