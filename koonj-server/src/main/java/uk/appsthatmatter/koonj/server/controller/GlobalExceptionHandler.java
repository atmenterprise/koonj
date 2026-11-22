package uk.appsthatmatter.koonj.server.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.appsthatmatter.koonj.server.dto.ErrorResponse;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException failure) {
        String message = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException failure) {
        return error(HttpStatus.BAD_REQUEST, failure.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception failure) {
        return error(HttpStatus.BAD_REQUEST, "Malformed JSON or invalid path or query parameter");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> conflict(DataIntegrityViolationException failure) {
        return error(HttpStatus.CONFLICT, "Request conflicts with an existing record or database constraint");
    }

    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ErrorResponse> webError(ErrorResponseException failure) {
        return error(failure.getStatusCode(), failure.getBody().getDetail());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(org.springframework.security.access.AccessDeniedException failure) {
        return error(HttpStatus.FORBIDDEN, "Insufficient permissions for this action");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void responseUnavailable(AsyncRequestNotUsableException failure, HttpServletResponse response) {
        LOG.debug("Response stream unavailable: {}", failure.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception failure) {
        if (failure instanceof org.springframework.web.ErrorResponse webError) {
            return error(webError.getStatusCode(), webError.getBody().getDetail());
        }
        LOG.error("Unhandled API failure", failure);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatusCode status, String message) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(),
                known == null ? "HTTP error" : known.getReasonPhrase(), message));
    }
}
