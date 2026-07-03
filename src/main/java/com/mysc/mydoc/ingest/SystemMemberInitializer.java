package com.mysc.mydoc.ingest;

import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.MemberRole;
import com.mysc.mydoc.repository.MemberRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SystemMemberInitializer {
    public static final String SYSTEM_MEMBER_EMAIL = "bot@mydoc.internal";

    @Bean
    ApplicationRunner ensureSystemMember(MemberRepository members) {
        return args -> members.findByEmail(SYSTEM_MEMBER_EMAIL)
                .orElseGet(() -> members.save(new Member(SYSTEM_MEMBER_EMAIL, "mydoc 봇", MemberRole.MEMBER)));
    }
}
