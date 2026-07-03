package com.mysc.mydoc.service;

import com.mysc.mydoc.common.ForbiddenException;
import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.MemberRole;
import com.mysc.mydoc.repository.MemberRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemberService {
    private final MemberRepository members;

    public MemberService(MemberRepository members) {
        this.members = members;
    }

    @Transactional
    public Member create(String email, String displayName, MemberRole role, UUID actorId) {
        requireAdmin(actorId);
        if (!StringUtils.hasText(email) || !StringUtils.hasText(displayName) || role == null) {
            throw new ValidationException("member email, displayName, role are required");
        }
        return members.save(new Member(email, displayName, role));
    }

    @Transactional(readOnly = true)
    public Member get(UUID memberId) {
        return members.findById(memberId)
                .orElseThrow(() -> new NotFoundException("member not found: " + memberId));
    }

    @Transactional(readOnly = true)
    public void requireAdmin(UUID memberId) {
        if (get(memberId).getRole() != MemberRole.ADMIN) {
            throw new ForbiddenException("admin required");
        }
    }
}
