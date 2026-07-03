package com.mysc.mydoc.api;

import static com.mysc.mydoc.api.dto.ApiDtos.MemberRequest;
import static com.mysc.mydoc.api.dto.ApiDtos.MemberResponse;

import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.service.MemberService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
public class MemberController {
    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    @PostMapping
    ResponseEntity<MemberResponse> create(
            @RequestBody MemberRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        Member member = members.create(request.email(), request.displayName(), request.role(), memberId);
        return ResponseEntity.created(URI.create("/api/members/" + member.getId())).body(toResponse(member));
    }

    @GetMapping("/me")
    MemberResponse me(@RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId) {
        return toResponse(members.get(memberId));
    }

    static MemberResponse toResponse(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getDisplayName(),
                member.getRole(),
                member.getSlackUserId(),
                member.getCreatedAt()
        );
    }
}
