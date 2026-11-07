package org.pipesor.pipeline.shared.interfaces.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    public static final String ATTRIBUTE = "pipeline.correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var supplied = request.getHeader(HEADER);
        var correlation = supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,128}")
                ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, correlation);
        response.setHeader(HEADER, correlation);
        chain.doFilter(request, response);
    }
}
