package com.relyon.economizaai.exception;

import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock private LocalizedMessageService messageService;
    @InjectMocks private GlobalExceptionHandler handler;

    @Test
    void unsupportedMethod_returns405_notInternalServerError() {
        when(messageService.translate("error.method.not.allowed")).thenReturn("Method not allowed");

        var response = handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(405, response.getBody().status());
        assertEquals("Method not allowed", response.getBody().message());
    }
}
