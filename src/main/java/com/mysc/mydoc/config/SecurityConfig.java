package com.mysc.mydoc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    // index.html이 인라인 <script>와 esm.sh(TipTap) 모듈을 쓰므로 둘은 허용하되,
    // 그 외 출처의 스크립트 주입·프레이밍·MIME 스니핑은 막는다 (DEPLOY-PLAN 프론트 게이트).
    private static final String CSP = "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline' https://esm.sh; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; "
            + "connect-src 'self' https://esm.sh; "
            + "font-src 'self' data:; "
            + "object-src 'none'; "
            + "frame-ancestors 'none'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
