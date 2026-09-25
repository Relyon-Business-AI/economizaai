package com.relyon.economizaai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestSizeLimitFilterTest {

    private static final long LIMIT = 5_242_880L; // 5MB

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(LIMIT);

    @Test
    void oversizedJsonBody_rejectedWith413_beforeChain() throws Exception {
        when(request.getContentType()).thenReturn("application/json");
        when(request.getContentLengthLong()).thenReturn(LIMIT + 1);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(413);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void smallJsonBody_passesThrough() throws Exception {
        when(request.getContentType()).thenReturn("application/json");
        when(request.getContentLengthLong()).thenReturn(1_000L);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void oversizedMultipart_isExempt_streamedAndBoundedElsewhere() throws Exception {
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=xyz");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void missingContentLength_passesThrough() throws Exception {
        when(request.getContentType()).thenReturn("application/json");
        when(request.getContentLengthLong()).thenReturn(-1L); // chunked / unknown

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
