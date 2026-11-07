package org.pipesor.pipeline.shared.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.NoSuchElementException;
import org.pipesor.pipeline.catalog.application.services.GitHubConnectionRequired;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable;
import org.pipesor.pipeline.catalog.application.services.PipelineRegistrationConflict;
import org.pipesor.pipeline.incident.domain.model.valueobjects.InvalidIncidentTransition;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @ExceptionHandler(GitHubVerificationUnavailable.class)
    ProblemDetail verificationUnavailable(GitHubVerificationUnavailable exception, HttpServletRequest request) {
        return problem(503, "Current GitHub verification unavailable", request);
    }

    @ExceptionHandler(GitHubConnectionRequired.class)
    ProblemDetail githubConnectionRequired(SecurityException exception, HttpServletRequest request) {
        var body = problem(403, "GitHub connection or reconnection required", request);
        body.setProperty("code", "GITHUB_CONNECTION_REQUIRED");
        return body;
    }

    @ExceptionHandler(SecurityException.class)
    ProblemDetail forbidden(SecurityException exception, HttpServletRequest request) {
        return problem(403, "Pipeline access denied", request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail missing(NoSuchElementException exception, HttpServletRequest request) {
        return problem(404, "Resource not found", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(400, exception.getMessage(), request);
    }

    @ExceptionHandler({PipelineRegistrationConflict.class, IllegalStateException.class, InvalidIncidentTransition.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception exception, HttpServletRequest request) {
        return problem(409, "Operation conflicts with current resource state", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ProblemDetail malformed(Exception exception, HttpServletRequest request) {
        return problem(400, "Malformed request", request);
    }

    public static void writeSecurityProblem(HttpServletResponse response,
            HttpServletRequest request, int status) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        var content = Map.of("type", "about:blank", "title", status == 401 ? "Unauthorized" : "Forbidden",
                "status", status, "correlationId", request.getAttribute(CorrelationFilter.ATTRIBUTE));
        response.getWriter().write(JSON.writeValueAsString(content));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse frameworkError) {
            var body = frameworkError.getBody();
            body.setProperty("correlationId", request.getAttribute(CorrelationFilter.ATTRIBUTE));
            return body;
        }
        System.getLogger(ApiExceptionHandler.class.getName()).log(System.Logger.Level.ERROR,
                "Request failed: correlation={0}, type={1}", request.getAttribute(CorrelationFilter.ATTRIBUTE), exception.getClass().getSimpleName());
        return problem(500, "Unexpected service failure", request);
    }

    private ProblemDetail problem(int status, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setProperty("correlationId", request.getAttribute(CorrelationFilter.ATTRIBUTE));
        return problem;
    }
}
