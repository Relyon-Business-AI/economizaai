package com.relyon.economizaai.security;

import com.relyon.economizaai.config.MdcContextFilter;
import com.relyon.economizaai.model.User;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void validToken_setsAuthenticationAndContinuesChain() throws Exception {
        var user = User.builder().email("test@test.com").password("encoded").build();
        when(jwtService.extractUsername("valid-token")).thenReturn("test@test.com");
        when(userDetailsService.loadUserByUsername("test@test.com")).thenReturn(user);
        when(jwtService.isTokenValid("valid-token", user)).thenReturn(true);

        var request = requestWithAuthorization("Bearer valid-token");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("test@test.com", authentication.getName());
        assertEquals("test@test.com", MDC.get(MdcContextFilter.USER_ID));
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingAuthorizationHeader_passesThroughUnauthenticated() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/receipts");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService, userDetailsService);
    }

    @Test
    void nonBearerAuthorizationHeader_passesThroughUnauthenticated() throws Exception {
        var request = requestWithAuthorization("Basic dXNlcjpwYXNz");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService, userDetailsService);
    }

    @Test
    void bearerKeywordWithoutToken_passesThroughUnauthenticated() throws Exception {
        var request = requestWithAuthorization("Bearer");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService, userDetailsService);
    }

    @Test
    void invalidOrExpiredToken_extractionThrows_passesThroughUnauthenticated() throws Exception {
        when(jwtService.extractUsername("expired-token")).thenThrow(new JwtException("expired"));

        var request = requestWithAuthorization("Bearer expired-token");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void tokenFailingValidation_doesNotSetAuthentication() throws Exception {
        var user = User.builder().email("test@test.com").password("encoded").build();
        when(jwtService.extractUsername("stale-token")).thenReturn("test@test.com");
        when(userDetailsService.loadUserByUsername("test@test.com")).thenReturn(user);
        when(jwtService.isTokenValid("stale-token", user)).thenReturn(false);

        var request = requestWithAuthorization("Bearer stale-token");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void existingAuthentication_isNotOverwritten() throws Exception {
        var existing = new UsernamePasswordAuthenticationToken("already@auth.com", null);
        SecurityContextHolder.getContext().setAuthentication(existing);
        when(jwtService.extractUsername("valid-token")).thenReturn("test@test.com");

        var request = requestWithAuthorization("Bearer valid-token");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertEquals(existing, SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(userDetailsService);
        verify(chain).doFilter(request, response);
    }

    @Test
    void nullUsernameFromToken_passesThroughUnauthenticated() throws Exception {
        when(jwtService.extractUsername(anyString())).thenReturn(null);

        var request = requestWithAuthorization("Bearer token-without-subject");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(userDetailsService);
    }

    private MockHttpServletRequest requestWithAuthorization(String headerValue) {
        var request = new MockHttpServletRequest("GET", "/api/v1/receipts");
        request.addHeader("Authorization", headerValue);
        return request;
    }
}
