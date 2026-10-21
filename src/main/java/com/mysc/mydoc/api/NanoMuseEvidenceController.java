package com.mysc.mydoc.api;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mysc.mydoc.repository.DocumentRepository;
import com.mysc.mydoc.service.KnowledgeGraphService;
import com.mysc.mydoc.service.KnowledgeContextService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public class NanoMuseEvidenceController {
    private final KnowledgeGraphService graph; private final KnowledgeContextService context;
    private final DocumentRepository documents; private final ObjectMapper json;
    public NanoMuseEvidenceController(KnowledgeGraphService graph,KnowledgeContextService context,DocumentRepository documents,ObjectMapper json){this.graph=graph;this.context=context;this.documents=documents;this.json=json;}
    @GetMapping("/api/knowledge/agent/evidence")
    ObjectNode evidence(@RequestParam String q){
        if(q.isBlank()||q.length()>4000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid query");
        var hits=graph.search(q,30);var labels=context.all();var titles=new HashMap<UUID,String>();
        documents.findAllById(hits.stream().map(KnowledgeGraphService.ScoredTriple::documentId).distinct().toList()).forEach(d->titles.put(d.getId(),d.getTitle()));
        var result=json.createObjectNode().put("query",q).put("scope","MYSCdoc graph candidates, not complete source documents");var rows=result.putArray("evidence");
        for(var t:hits){
            var row=rows.addObject().put("id",t.id().toString()).put("documentId",t.documentId().toString()).put("title",titles.getOrDefault(t.documentId(),""))
                .put("subject",t.subject()).put("predicate",t.predicate()).put("object",t.object()).put("statement",t.statement());
            if(labels.containsKey(t.id()))row.set("unreviewedClassification",labels.get(t.id()));
        }
        return result;
    }
}
