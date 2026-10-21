package com.mysc.mydoc.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.service.KnowledgeGraphService.ScoredTriple;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ContextualGraphRetrieverTest {
    private final ObjectMapper json=new ObjectMapper();
    private List<ScoredTriple> candidates(){return List.of(new ScoredTriple(UUID.randomUUID(),UUID.randomUUID(),"risk","파트너십 보장 없음","기업","상태","미확정",null,null,1));}
    @Test void permitsAbstentionButNeverInventsOrDuplicatesEvidence() throws Exception {
        var c=candidates();
        assertThat(ContextualGraphRetriever.select(c,json.readTree("[]"))).isEmpty();
        assertThat(ContextualGraphRetriever.select(c,json.readTree("[0]"))).containsExactly(c.getFirst());
        for(String invalid:List.of("[0,0]","[1]","[-1]","[0.0]","[\"0\"]","{}"))
            assertThatThrownBy(()->ContextualGraphRetriever.select(c,json.readTree(invalid))).isInstanceOf(IllegalArgumentException.class);
    }
}
