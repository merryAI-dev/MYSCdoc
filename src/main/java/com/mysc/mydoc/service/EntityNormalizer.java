package com.mysc.mydoc.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 지식 트리플의 개체(subject/object)를 그래프 노드로 쓸 수 있게 정제한다.
 *
 * 배경: 실측 결과(2026-07-13) 트리플 2,448개 중 97.9%의 개체가 단일 문서에 고립돼 있었다.
 * 주원인은 LLM이 개체 자리에 완결 문장을 넣는 것("MYSC는 ~하기로 했어요" 전체가 노드) —
 * 문장은 두 번 다시 같게 등장하지 않으므로 그래프 연결이 수학적으로 생기지 않는다.
 * 개체는 명사구여야 하고, 같은 실체는 하나의 표기로 수렴해야 소스(Slack·Tiro·Drive)를
 * 가로지르는 연결이 만들어진다.
 */
@Service
public class EntityNormalizer {
    /** 정규화 후에도 이 길이를 넘으면 명사구가 아니라 서술로 본다. */
    private static final int MAX_ENTITY_LENGTH = 40;
    private static final int MAX_PREDICATE_LENGTH = 30;

    /**
     * 완결 문장 어미 — 개체 자리에 오면 안 되는 형태.
     * 구어체(해요/합니다)·문어체(이다/하였다)·명사형(함/했음)·의문형(인가요/할까요)을 모두 포함한다
     * (Drive 회의록은 문어체·명사형이 흔해 이들을 놓치면 문장이 노드로 들어온다).
     */
    private static final Pattern SENTENCE_ENDING = Pattern.compile(
            ".*(해요|했어요|해야|합니다|했습니다|한다|했다|하였다|였다|이다|입니다|이에요|예요|에요"
            + "|하기로 함|하기로 했|하지 않아요|않는다|않았다|된다|됐다|되었다|됩니다|됐습니다"
            + "|함|했음|였음|됨|임|인가요|할까요|나요|을까요|ㄹ까요)[.!?]?\\s*$");

    /**
     * 병기 분리 후보: 짧은 이름들을 co-list 구분자로 나열한 형태 ("혜윰, 보람" / "MYSC · 멘토리").
     * 구분자는 콤마와 가운뎃점(U+00B7·, U+30FB・)만 — 슬래시(/)는 A/B, N/A, OKR/KPI, 날짜(2024/03/15)처럼
     * 단일 토큰 내부에 흔해 co-list로 오인하면 개체를 파편화한다.
     */
    private static final Pattern CO_LISTED = Pattern.compile("^[^,·・]{1,20}([,·・]\\s*[^,·・]{1,20}){1,2}$");
    private static final Pattern CO_LIST_SPLIT = Pattern.compile("[,·・]");

    /** co-list로 쪼갠 뒤 이 조각이 나오면 원래 하나의 개체였다는 신호 — 분리하지 않고 원형 유지. */
    private static final Pattern LEGAL_SUFFIX = Pattern.compile("(?i)^(inc|ltd|llc|co|corp|주식회사|㈜)\\.?$");

    /**
     * 같은 실체의 표기 변형 → 대표 표기. 조직 고유명은 여기서 수렴시킨다.
     * (소문자 비교. 사전이 커지면 DB 설정으로 옮기는 게 과제 — 지금은 최소 사전으로 시작.)
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("엠와이소셜컴퍼니", "MYSC"),
            Map.entry("(주)엠와이소셜컴퍼니", "MYSC"),
            Map.entry("주식회사 엠와이소셜컴퍼니", "MYSC"),
            Map.entry("mysc", "MYSC"),
            Map.entry("엠와이씨소셜컴퍼니", "MYSC"),
            Map.entry("팀 전체", "팀"),
            Map.entry("우리 팀", "팀"),
            Map.entry("전체 팀", "팀")
    );

    /** '님'으로 끝나지만 존칭 접미가 아니라 그 자체가 하나의 명사인 것들 — 절단하면 다른 뜻이 된다. */
    private static final Set<String> NON_HONORIFIC_NIM = Set.of(
            "하나님", "하느님", "선생님", "부처님", "임님", "손님", "스승님");

    /**
     * 개체 하나를 정규화한다. 명사구로 볼 수 없으면(문장형·과대 길이) 빈 Optional.
     */
    public Optional<String> entity(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        String value = raw.strip().replaceAll("\\s+", " ");
        // 명사구 끝의 마침표는 표기 노이즈로 제거하되, 문장 어미 판정은 제거 전 형태 기준.
        if (SENTENCE_ENDING.matcher(value).matches()) {
            return Optional.empty();
        }
        while (value.endsWith(".") || value.endsWith("。")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        if (value.endsWith("님") && value.length() >= 3 && !NON_HONORIFIC_NIM.contains(value)) {
            value = value.substring(0, value.length() - 1);
        }
        String alias = ALIASES.get(value.toLowerCase(Locale.ROOT));
        if (alias != null) {
            value = alias;
        }
        if (!StringUtils.hasText(value) || value.length() > MAX_ENTITY_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * 병기된 개체("혜윰, 보람")를 개별 개체로 분리한다.
     * 분리 대상이 아니면 원형 그대로 1개짜리 리스트. 각 원소는 이미 entity() 정규화를 통과한 값.
     * 단, 쪼갠 조각 중 법인 접미("Inc." 등)가 있으면 원래 한 개체였다고 보고 분리하지 않는다.
     */
    public List<String> entities(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String value = raw.strip();
        if (CO_LISTED.matcher(value).matches()) {
            String[] parts = CO_LIST_SPLIT.split(value);
            boolean looksLikeLegalName = java.util.Arrays.stream(parts)
                    .map(String::strip)
                    .anyMatch(part -> LEGAL_SUFFIX.matcher(part).matches());
            if (!looksLikeLegalName) {
                List<String> split = java.util.Arrays.stream(parts)
                        .map(this::entity)
                        .flatMap(Optional::stream)
                        .distinct()
                        .toList();
                if (!split.isEmpty()) {
                    return split;
                }
            }
        }
        return entity(value).map(List::of).orElse(List.of());
    }

    /** 서술어(predicate) 정규화 — 지나치게 길면 관계가 아니라 서술이므로 거부. */
    public Optional<String> predicate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        String value = raw.strip().replaceAll("\\s+", " ");
        if (value.length() > MAX_PREDICATE_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
