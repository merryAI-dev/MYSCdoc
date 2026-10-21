package com.mysc.mydoc.api;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Local development pairing only. Never returns the provider API key. */
@Profile("marine-local")
@RestController
public class NanoMuseLaunchController {
    @GetMapping("/api/knowledge/agent/launch")
    ResponseEntity<Map<String,String>> launch(){
        try {
            var token=Files.readString(Path.of(".nanomuse-local/server_token")).strip();
            if(!token.matches("[A-Za-z0-9_-]{20,100}"))throw new IllegalStateException();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("url","http://127.0.0.1:8787/#token="+token));
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"nanoMuse 로컬 서비스를 먼저 실행해 주세요.");}
    }
}
