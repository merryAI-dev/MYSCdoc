package com.mysc.mydoc.api;

import static com.mysc.mydoc.api.dto.ApiDtos.SpaceRequest;
import static com.mysc.mydoc.api.dto.ApiDtos.SpaceResponse;

import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.domain.Space;
import com.mysc.mydoc.service.SpaceService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/spaces")
public class SpaceController {
    private final SpaceService spaces;

    public SpaceController(SpaceService spaces) {
        this.spaces = spaces;
    }

    @PostMapping
    ResponseEntity<SpaceResponse> create(
            @RequestBody SpaceRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        Space space = spaces.create(request.slug(), request.name(), memberId);
        return ResponseEntity.created(URI.create("/api/spaces/" + space.getId())).body(toResponse(space));
    }

    @GetMapping
    List<SpaceResponse> list() {
        return spaces.list().stream().map(SpaceController::toResponse).toList();
    }

    private static SpaceResponse toResponse(Space space) {
        return new SpaceResponse(space.getId(), space.getSlug(), space.getName(), space.getCreatedAt());
    }
}
