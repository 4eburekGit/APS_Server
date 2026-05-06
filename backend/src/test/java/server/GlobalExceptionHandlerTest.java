package server;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleResponseStatus_shouldReturnCorrectStatusAndBody() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        var response = handler.handleResponseStatus(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found", response.getBody());
    }

    @Test
    void handleResponseStatus_shouldHandleForbidden() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        var response = handler.handleResponseStatus(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Access denied", response.getBody());
    }

    @Test
    void handleRuntime_shouldReturn500WithMessage() {
        RuntimeException ex = new RuntimeException("Something went wrong");
        var response = handler.handleRuntime(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Something went wrong", response.getBody());
    }

    @Test
    void handleRuntime_shouldHandleNullMessage() {
        RuntimeException ex = new RuntimeException((String) null);
        var response = handler.handleRuntime(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNull(response.getBody());
    }
}
