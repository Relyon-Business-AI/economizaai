package com.relyon.economizaai.exception;

import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

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

    @Test
    void connectionPoolTimeout_returnsLocalized503WithRetryAfter_notInternalServerError() {
        when(messageService.translate("error.overloaded")).thenReturn("Estamos com muito acesso agora.");
        var poolTimeout = new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new IllegalStateException("Unable to acquire JDBC Connection",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 6000ms")));

        var response = handler.handleDatabaseUnavailable(poolTimeout);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getBody().status());
        assertEquals("Estamos com muito acesso agora.", response.getBody().message());
        assertEquals("5", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void otherDatabaseFailure_staysAGeneric500() {
        when(messageService.translate("error.internal")).thenReturn("Algo deu errado");
        var databaseDown = new DataAccessResourceFailureException("connection refused",
                new SQLNonTransientConnectionException("Connection refused"));

        var response = handler.handleDatabaseUnavailable(databaseDown);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }
}
