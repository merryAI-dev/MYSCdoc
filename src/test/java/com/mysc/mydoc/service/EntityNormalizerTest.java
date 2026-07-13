package com.mysc.mydoc.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** M16-①②: 개체 정규화 — 그래프 노드가 될 수 없는 문장형 개체를 걸러내고 표기를 수렴시킨다. */
class EntityNormalizerTest {
    private final EntityNormalizer normalizer = new EntityNormalizer();

    @Test
    void acceptsNounPhrasesAndNormalizesWhitespace() {
        assertThat(normalizer.entity("결제  재시도   로직")).contains("결제 재시도 로직");
        assertThat(normalizer.entity(" 배포 요일. ")).contains("배포 요일");
    }

    @Test
    void rejectsSentenceLikeEntities() {
        // 실측에서 발견된 실제 유형: 개체 자리에 완결 문장이 통째로 들어온 경우
        assertThat(normalizer.entity("MYSC는 다음 정기 미팅부터 진행 상황을 PPT로 준비하여 공유해요.")).isEmpty();
        assertThat(normalizer.entity("아름다운재단은 협업 여부를 최종 결정하고 MYSC에 통보하기로 했다")).isEmpty();
        assertThat(normalizer.entity("배포는 화요일에 합니다")).isEmpty();
        // 문어체·명사형 어미(Drive 회의록에 흔함) — 리뷰에서 놓쳤던 유형
        assertThat(normalizer.entity("회의를 다음 주로 미루기로 결정했음")).isEmpty();
        assertThat(normalizer.entity("MYSC는 소셜벤처 액셀러레이터이다")).isEmpty();
        assertThat(normalizer.entity("예산은 이미 확정되었다")).isEmpty();
    }

    @Test
    void rejectsOverlongEntities() {
        assertThat(normalizer.entity("가".repeat(41))).isEmpty();
    }

    @Test
    void mergesAliasesToCanonicalName() {
        assertThat(normalizer.entity("엠와이소셜컴퍼니")).contains("MYSC");
        assertThat(normalizer.entity("mysc")).contains("MYSC");
        assertThat(normalizer.entity("우리 팀")).contains("팀");
        assertThat(normalizer.entity("보람님")).contains("보람");
    }

    @Test
    void keepsNounsThatMerelyEndInNim() {
        // '님'으로 끝나지만 존칭이 아닌 명사는 절단하면 안 된다 (하나님→하나 충돌 방지)
        assertThat(normalizer.entity("하나님")).contains("하나님");
        assertThat(normalizer.entity("선생님")).contains("선생님");
    }

    @Test
    void splitsCoListedEntities() {
        assertThat(normalizer.entities("혜윰, 보람")).containsExactly("혜윰", "보람");
        assertThat(normalizer.entities("MYSC · 멘토리")).containsExactly("MYSC", "멘토리");
        // 병기가 아닌 단일 개체는 그대로
        assertThat(normalizer.entities("결제 API")).containsExactly("결제 API");
    }

    @Test
    void doesNotShredSingleTokensContainingSlashOrLegalSuffix() {
        // 슬래시는 co-list 구분자가 아니다 — 단일 개념을 파편화하면 안 된다
        assertThat(normalizer.entities("A/B 테스트")).containsExactly("A/B 테스트");
        assertThat(normalizer.entities("N/A")).containsExactly("N/A");
        assertThat(normalizer.entities("OKR/KPI 지표")).containsExactly("OKR/KPI 지표");
        // 법인 접미가 든 콤마 표기는 병기가 아니라 한 개체명 (끝 마침표는 노이즈로 제거됨)
        assertThat(normalizer.entities("MYSC, Inc.")).containsExactly("MYSC, Inc");
    }

    @Test
    void predicateRejectsOverlongRelations() {
        assertThat(normalizer.predicate("담당한다")).contains("담당한다");
        assertThat(normalizer.predicate("이 결정은 다음 분기 예산 편성과 인력 배치 계획에 직접적인 영향을 준다")).isEmpty();
    }
}
