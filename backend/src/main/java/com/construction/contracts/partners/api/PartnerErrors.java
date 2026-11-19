package com.construction.contracts.partners.api;

import com.construction.contracts.partners.application.PartnerProblem;
import com.construction.contracts.projects.application.ProjectProblem;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = PartnerController.class)
class PartnerErrors {
    @ExceptionHandler(PartnerProblem.class)
    ProblemDetail partner(PartnerProblem error) {
        return problem(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(ProjectProblem.class)
    ProblemDetail project(ProjectProblem error) {
        return problem(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail version(ResponseStatusException error) {
        var detail = error.getBody();
        detail.setProperty("code", error.getStatusCode().value() == 428 ? "version_required" : "stale_version");
        return detail;
    }

    private ProblemDetail problem(int status, String code, String message) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), message);
        detail.setProperty("code", code);
        return detail;
    }
}
