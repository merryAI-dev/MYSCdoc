package com.mysc.mydoc.local;

import com.fasterxml.jackson.databind.*;
import com.mysc.mydoc.domain.*;
import com.mysc.mydoc.service.*;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.archive.DecisionExtractPort;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.*;

/** Explicit local-only corpus import, using the app's normal document and extraction services. */
@Component
@Profile("marine-local")
public class MarineLocalBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MarineLocalBootstrap.class);
    public static final UUID MEMBER = UUID.fromString("762538ab-40e5-47fc-a3a2-83ba0d729001");
    private static final UUID SPACE = UUID.fromString("762538ab-40e5-47fc-a3a2-83ba0d729002");
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    private final DocumentService documents; private final ChunkingService chunks;
    private final DecisionExtractPort extractor; private final KnowledgeTripleWriter writer;
    private final TransactionTemplate tx; private final String corpus;
    public MarineLocalBootstrap(JdbcTemplate jdbc, ObjectMapper json, DocumentService documents,
            ChunkingService chunks, DecisionExtractPort extractor, KnowledgeTripleWriter writer,
            PlatformTransactionManager manager, @Value("${marine.corpus}") String corpus,
            @Value("${spring.datasource.url}") String url, @Value("${server.address:}") String address) {
        if (!url.equals("jdbc:postgresql://127.0.0.1:55436/myscdoc_marine") || !address.equals("127.0.0.1"))
            throw new IllegalArgumentException("marine-local requires the isolated loopback marine database and server");
        this.jdbc=jdbc;this.json=json;this.documents=documents;this.chunks=chunks;
        this.extractor=extractor;this.writer=writer;this.tx=new TransactionTemplate(manager);this.corpus=corpus;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        var data=json.readTree(Path.of(corpus).toFile());
        jdbc.update("INSERT INTO member(id,email,display_name,role,created_at) VALUES (?, 'marine-local@example.invalid','해양수산 로컬 테스트','ADMIN',now()) ON CONFLICT DO NOTHING",MEMBER);
        jdbc.update("INSERT INTO space(id,slug,name,created_at) VALUES (?,'marine-local','2026 해양수산 AC',now()) ON CONFLICT DO NOTHING",SPACE);
        jdbc.execute("CREATE TABLE IF NOT EXISTS marine_local_import (source_id text PRIMARY KEY, document_id uuid REFERENCES document(id), sha256 text NOT NULL, graph_status text NOT NULL DEFAULT 'pending')");
        List<Runnable> jobs=new ArrayList<>();
        for (JsonNode d:data) {
            String sourceId=d.path("id").asText(), text=d.path("text").asText(), hash=d.path("sha256").asText();
            if (text.isBlank()) continue;
            UUID docId=tx.execute(status -> {
                var ids=jdbc.queryForList("SELECT document_id FROM marine_local_import WHERE source_id=? AND sha256=?",UUID.class,sourceId,hash);
                if (!ids.isEmpty()) return ids.getFirst();
                String title=Path.of(d.path("member").asText()).getFileName().toString();
                var doc=documents.create(SPACE,title,MEMBER);
                List<BlockPayload> payloads=new ArrayList<>();
                for (String line:text.split("\\n+")) {
                    if (line.isBlank()) continue;
                    var content=json.createObjectNode().put("type","paragraph");
                    content.putArray("content").addObject().put("type","text").put("text",line);
                    payloads.add(new BlockPayload(BlockType.PARAGRAPH,content,SourceType.IMPORT,null,"marine:"+sourceId));
                }
                documents.replaceBlocks(doc.getId(),payloads,MEMBER,ChangeCause.MANUAL);
                jdbc.update("INSERT INTO marine_local_import(source_id,document_id,sha256) VALUES (?,?,?)",sourceId,doc.getId(),hash);
                return doc.getId();
            });
            chunks.rechunk(docId);
            if (!java.util.Set.of("done", "empty").contains(jdbc.queryForObject("SELECT graph_status FROM marine_local_import WHERE source_id=?",String.class,sourceId))) {
                jobs.add(() -> {
                    try {
                        var result=extractor.extract(List.of(new SlackMessage("marine",Path.of(d.path("member").asText()).getFileName().toString(),text,sourceId)));
                        result.ifPresent(value -> tx.executeWithoutResult(status -> writer.replace(docId,value,"marine",sourceId)));
                        jdbc.update("UPDATE marine_local_import SET graph_status=? WHERE source_id=?",result.isPresent()?"done":"empty",sourceId);
                        log.info("marine_import source={} graph={}",sourceId,result.isPresent()?"done":"empty");
                    } catch (Exception ex) {
                        jdbc.update("UPDATE marine_local_import SET graph_status='failed' WHERE source_id=?",sourceId);
                        log.warn("marine_import source={} graph=failed error_type={}",sourceId,ex.getClass().getSimpleName());
                    }
                });
            }
        }
        log.info("marine_import documents_ready={} graph_jobs={}",data.size(),jobs.size());
        Thread.ofVirtual().name("marine-local-extraction").start(() -> {
            try (var pool=Executors.newFixedThreadPool(3)) { for (var job:jobs) pool.submit(job); }
            log.info("marine_import graph_batch_finished");
        });
    }
}
