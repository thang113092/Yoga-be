package com.company.yoga.config;

import com.company.yoga.identity.account.service.SupabaseAuthGateway;
import com.company.yoga.identity.account.service.SupabaseProfileService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final com.company.yoga.identity.account.repository.RoleRepository roles;
    private final SupabaseAuthGateway supabase;
    private final SupabaseProfileService profiles;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = extractJwtFromRequest(request);
            if (StringUtils.hasText(jwt)) {
                var identity = supabase.verifiedUser(jwt);
                var user = profiles.resolve(identity);
                String userId = user.getId().toString();
                if (!Boolean.TRUE.equals(user.getIsActive())) throw new IllegalArgumentException("Inactive account");
                String roleCode = roles.findById(user.getRoleId()).orElseThrow().getCode();

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                if (roleCode != null && !roleCode.isBlank()) {
                    String upperRole = roleCode.trim().toUpperCase();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + upperRole));
                    authorities.add(new SimpleGrantedAuthority(upperRole));
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
            int status = ex instanceof com.company.yoga.common.exception.BusinessException business ? business.getErrorCode().getHttpStatusCode() : 401;
            response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
            String message = ex instanceof com.company.yoga.common.exception.BusinessException ? ex.getMessage() : "Phiên xác thực không hợp lệ.";
            response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("success",false,"code","AUTHENTICATION_FAILED","message",message)));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
