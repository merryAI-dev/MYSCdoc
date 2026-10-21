package com.mysc.mydoc.service;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeContextService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public KnowledgeContextService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public Map<UUID,JsonNode> all() {
        Map<UUID,JsonNode> result=new HashMap<>();
        jdbc.query("SELECT triple_id,classification::text FROM knowledge_context", rs -> {
            try { result.put(rs.getObject(1,UUID.class),json.readTree(rs.getString(2))); }
            catch (java.io.IOException ex) { throw new IllegalStateException("Invalid saved classification"); }
        });
        return result;
    }
}
