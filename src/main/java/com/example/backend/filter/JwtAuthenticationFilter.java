package com.example.backend.filter;

import com.example.backend.model.Member;
import com.example.backend.model.enumSet.MemberActiveEnum;
import com.example.backend.repository.MemberRepository;
import com.example.backend.util.TokenProvider;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenProvider tokenProvider;
    private final MemberRepository memberRepository;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {

        String requestURI = request.getRequestURI();

        // 인증이 필요 없는 경로 예외 처리
        if (requestURI.startsWith("/api/auth/")) {
            filterChain.doFilter(request, response);

            return;
        }

        // 쿠키에서 액세스 토큰 가져오기
        String accessToken = tokenProvider.resolveAccessToken(request);

        if (accessToken != null) {
            try {
                // accessToken 유효성 검증
                if (tokenProvider.validateToken(accessToken)) {
                    setAuthentication(accessToken);
                }
            } catch (ExpiredJwtException e) {
                log.info("Access token이 만료되었습니다. Refresh Token으로 갱신을 시도합니다...");

                String loginId = e.getClaims().getSubject();
                Long memberId = e.getClaims().get("memberId", Long.class);
                String refreshToken = tokenProvider.getRefreshTokenFromRedis("RT:" + loginId);

                // Refresh Token 유효성 검증 후 새 Access Token 발급
                if (refreshToken == null) {
                    log.warn("Refresh Token이 없습니다. loginId: {}", loginId);
                } else if (!tokenProvider.validateToken(refreshToken)) {
                    log.warn("유효하지 않은 Refresh Token입니다. loginId: {}", loginId);
                } else {
                    String newAccessToken = tokenProvider.createAccessToken(loginId, memberId);
                    tokenProvider.setAccessTokenCookie(newAccessToken, response);
                    setAuthentication(newAccessToken);
                    log.info("새로운 Access Token이 발급되었습니다. loginId: {}", loginId);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void setAuthentication(String token) {
        Long memberId = tokenProvider.getMemberIdFromToken(token);

        // 멤버 유효성 검사
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AccessDeniedException("존재하지 않는 회원입니다."));

        // 멤버 탈퇴 여부 검사
        if (MemberActiveEnum.INACTIVE.equals(member.getActivity())) {
            throw new AccessDeniedException("비활성화된 회원입니다. 접근이 거부됩니다.");
        }


        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(memberId, null, null);

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

}
