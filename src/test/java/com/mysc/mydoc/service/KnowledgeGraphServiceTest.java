package com.mysc.mydoc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mysc.mydoc.domain.KnowledgeTriple;
import com.mysc.mydoc.repository.KnowledgeTripleRepository;
import com.mysc.mydoc.service.KnowledgeGraphService.ScoredTriple;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeGraphServiceTest {

    @Test
    void strongSeeds_keepsOnlySeedsWithinSeventyPercentOfBest() {
        List<ScoredTriple> seeds = List.of(
                triple("해양수산 데모데이", 43.5),
                triple("해양수산 데모데이", 43.3),
                triple("해양수산 운영사 데모데이", 42.9),
                triple("KIMST", 25.1));

        assertThat(KnowledgeGraphService.strongSeeds(seeds))
                .extracting(ScoredTriple::subject)
                .containsExactly("해양수산 데모데이", "해양수산 데모데이", "해양수산 운영사 데모데이");
    }

    @Test
    void graphAwareSearch_returnsEdgesConnectedToMatchedEntitiesNotLexicalDistractors() {
        KnowledgeTripleRepository repository = mock();
        when(repository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(
                stored("해양수산 데모데이", "포함한다", "통합 데모데이", "두 데모데이로 구성해요"),
                stored("해양수산 데모데이", "포함한다", "운영사 데모데이", "운영사 행사를 먼저 열어요"),
                stored("통합 데모데이", "일정", "8월", "통합 행사는 8월이에요"),
                stored("투자자 섭외", "활용한다", "VC", "해양수산 데모데이 해양수산 데모데이")));
        KnowledgeGraphService service = new KnowledgeGraphService(repository);

        List<ScoredTriple> hits = service.searchGraphAware("해양수산 데모데이", 3, 12);

        assertThat(hits)
                .extracting(ScoredTriple::subject)
                .contains("해양수산 데모데이")
                .doesNotContain("투자자 섭외");
        assertThat(hits).allMatch(hit -> hit.subject().contains("데모데이")
                || hit.object().contains("데모데이"));
    }

    private static ScoredTriple triple(String subject, double score) {
        return new ScoredTriple(UUID.randomUUID(), UUID.randomUUID(), "convention", subject,
                subject, "포함한다", "통합 데모데이", Instant.now(), null, score);
    }

    private static KnowledgeTriple stored(String subject, String predicate, String object, String statement) {
        return new KnowledgeTriple(UUID.randomUUID(), "convention", statement,
                subject, predicate, object, "C1", "1.0", Instant.now());
    }
}
