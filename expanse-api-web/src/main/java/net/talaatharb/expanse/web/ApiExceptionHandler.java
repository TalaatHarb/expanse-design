package net.talaatharb.expanse.web;

import net.talaatharb.expanse.domain.DesignException;
import net.talaatharb.expanse.dto.ApiModels.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DesignException.class)
    public ResponseEntity<ApiError> design(DesignException e) {
        HttpStatus status = switch (e.code()) {
            case "PROJECT_NOT_FOUND", "DEFINITION_NOT_FOUND", "COMPONENT_NOT_FOUND", "NET_NOT_FOUND",
                 "TRACE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "REVISION_CONFLICT", "PROJECT_EXISTS" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage(), e.currentRevision()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            LOG.error("Framework API failure", e);
        } else {
            LOG.debug("Rejected API request: {}", e.toString());
        }
        return new ResponseEntity<>(new ApiError(status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR",
                "Request failed. Check method, route, required fields, IDs, numeric values and field sizes.", null),
                headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        LOG.error("Unhandled API failure", e);
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", "Server operation failed; consult the server log.", null));
    }
}
