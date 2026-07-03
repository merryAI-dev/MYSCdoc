package com.mysc.mydoc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HeaderAuthFilter extends OncePerRequestFilter {

    public static final String MEMBER_ID_ATTRIBUTE = "memberId";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String internalServiceToken;

    public HeaderAuthFilter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${mydoc.internal-service-token}") String internalServiceToken
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            if (path.equals("/api/internal/collab-tokens")) {
                if (!authenticateMember(request, response, path)) {
                    return;
                }
            } else if (path.startsWith("/api/internal/")) {
                if (StringUtils.hasText(internalServiceToken)
                        && ("Bearer " + internalServiceToken).equals(request.getHeader("Authorization"))) {
                    filterChain.doFilter(request, response);
                    return;
                }
                writeProblem(response, HttpStatus.UNAUTHORIZED, "Missing or invalid internal token", path);
                return;
            } else if (path.startsWith("/api/") || path.equals("/mcp")) {
                if (!authenticateMember(request, response, path)) {
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean authenticateMember(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        UUID memberId = parseUuid(request.getHeader("X-Member-Id"));
        if (memberId == null || !memberExists(memberId)) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Missing or invalid X-Member-Id", path);
            return false;
        }
        request.setAttribute(MEMBER_ID_ATTRIBUTE, memberId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberId, null, List.of())
        );
        return true;
    }

    private UUID parseUuid(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean memberExists(UUID memberId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE id = ?", Integer.class, memberId);
        return count != null && count > 0;
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail, String path) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(path));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
