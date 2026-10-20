package com.relyon.economizaai.exception;

import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock private LocalizedMessageService messageService;
    @InjectMocks private GlobalExceptionHandler handler;

    @Test
    void missingRequiredParameter_returns400_notInternalServerError() {
        when(messageService.translate("validation.failed")).thenReturn("Dados inválidos");

        var response = handler.handleMissingParameter(new MissingServletRequestParameterException("userIds", "List"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().status());
        assertEquals("Dados inválidos", response.getBody().message());
    }

    @Test
    void unsupportedMethod_returns405_notInternalServerError() {
        when(messageService.translate("error.method.not.allowed")).thenReturn("Method not allowed");

        var response = handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(405, response.getBody().status());
        assertEquals("Method not allowed", response.getBody().message());
    }
}
