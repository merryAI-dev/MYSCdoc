package com.mysc.mydoc.service;

import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.Space;
import com.mysc.mydoc.repository.SpaceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SpaceService {
    private final SpaceRepository spaces;
    private final MemberService members;

    public SpaceService(SpaceRepository spaces, MemberService members) {
        this.spaces = spaces;
        this.members = members;
    }

    @Transactional
    public Space create(String slug, String name, UUID actorId) {
        members.requireAdmin(actorId);
        if (!StringUtils.hasText(slug) || !StringUtils.hasText(name)) {
            throw new ValidationException("space slug and name are required");
        }
        return spaces.save(new Space(slug, name));
    }

    @Transactional(readOnly = true)
    public List<Space> list() {
        return spaces.findAll();
    }
}
