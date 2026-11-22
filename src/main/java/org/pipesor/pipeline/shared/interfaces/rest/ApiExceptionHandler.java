package org.pipesor.pipeline.shared.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import org.pipesor.pipeline.catalog.application.services.GitHubConnectionRequired;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable;
import org.pipesor.pipeline.catalog.application.services.PipelineRegistrationConflict;
import org.pipesor.pipeline.incident.domain.model.valueobjects.InvalidIncidentTransition;
import org.pipesor.pipeline.incident.interfaces.rest.DiagnosisEntitlementController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Narrow mapping to {@code application/problem+json} with a stable {@code code} and the correlation id.
 * Exception messages are never returned; unexpected failures are 500 and logged with their class.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ExceptionHandler(GitHubVerificationUnavailable.class)
    ResponseEntity<ProblemDetail> verificationUnavailable(GitHubVerificationUnavailable exception, HttpServletRequest request) {
        LOG.warn("GitHub verification unavailable: correlation={}", correlation(request));
        return problem(503, "GITHUB_VERIFICATION_UNAVAILABLE", "Current GitHub verification unavailable", request);
    }

    @ExceptionHandler(GitHubConnectionRequired.class)
    ResponseEntity<ProblemDetail> githubConnectionRequired(GitHubConnectionRequired exception, HttpServletRequest request) {
        LOG.info("GitHub connection required: correlation={}", correlation(request));
        return problem(403, "GITHUB_CONNECTION_REQUIRED", "GitHub connection or reconnection required", request);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ProblemDetail> forbidden(SecurityException exception, HttpServletRequest request) {
        LOG.info("Access denied: correlation={}", correlation(request));
        return problem(403, "ACCESS_DENIED", "Pipeline access denied", request);
    }

    @ExceptionHandler(DiagnosisEntitlementController.IncidentNotFound.class)
    ResponseEntity<ProblemDetail> incidentMissing(NoSuchElementException exception, HttpServletRequest request) {
        return problem(404, "INCIDENT_NOT_FOUND", "Incident not found", request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missing(NoSuchElementException exception, HttpServletRequest request) {
        return problem(404, "NOT_FOUND", "Resource not found", request);
    }

    @ExceptionHandler({PipelineRegistrationConflict.class, InvalidIncidentTransition.class, DataIntegrityViolationException.class})
    ResponseEntity<ProblemDetail> conflict(Exception exception, HttpServletRequest request) {
        LOG.info("Request conflicts with current state: correlation={}, type={}", correlation(request),
                exception.getClass().getSimpleName());
        return problem(409, "CONFLICT", "Operation conflicts with current resource state", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> malformed(Exception exception, HttpServletRequest request) {
        LOG.debug("Malformed request: correlation={}, type={}", correlation(request), exception.getClass().getSimpleName());
        return problem(400, "INVALID_REQUEST", "Malformed request", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse frameworkError && frameworkError.getStatusCode().is4xxClientError()) {
            int status = frameworkError.getStatusCode().value();
            String code = switch (status) {
                case 404 -> "NOT_FOUND"; case 405 -> "METHOD_NOT_ALLOWED"; case 406 -> "NOT_ACCEPTABLE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE"; default -> "INVALID_REQUEST";
            };
            return problem(status, code, HttpStatus.valueOf(status).getReasonPhrase(), request);
        }
        LOG.error("Request failed: correlation={}, type={}", correlation(request), exception.getClass().getName(), exception);
        return problem(500, "INTERNAL_ERROR", "Unexpected service failure", request);
    }

    /** Problem body for 401/403 written by the security filter chain. */
    public static void writeSecurityProblem(HttpServletResponse response, HttpServletRequest request, int status) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var content = new LinkedHashMap<String, Object>();
        content.put("type", "about:blank");
        content.put("title", status == 401 ? "Unauthorized" : "Forbidden");
        content.put("status", status);
        content.put("code", status == 401 ? "UNAUTHENTICATED" : "ACCESS_DENIED");
        content.put("correlationId", correlation(request));
        response.getOutputStream().write(JSON.writeValueAsBytes(content));
    }

    private static Object correlation(HttpServletRequest request) {
        return request.getAttribute(CorrelationFilter.ATTRIBUTE);
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlation(request));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
