package dev.fsp.app.api;

import dev.fsp.app.api.SystemService.SpecValidationException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the application's failure modes into problem responses the editor can act on.
 *
 * <p>Validation failures carry the individual issues rather than a flattened message, because the
 * editor highlights the offending node and needs to know which one it is.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), "not-found");
    }

    @ExceptionHandler(SpecValidationException.class)
    ProblemDetail invalidSpec(SpecValidationException e) {
        // UNPROCESSABLE_CONTENT is the current name for 422; the ENTITY spelling is deprecated.
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid system specification",
                e.getMessage(), "invalid-spec");
        detail.setProperty("issues", e.issues());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), "bad-request");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        log.warn("request could not be completed", e);
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), "conflict");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://feedback-systems-playground.local/problems/" + type));
        return problem;
    }
}
