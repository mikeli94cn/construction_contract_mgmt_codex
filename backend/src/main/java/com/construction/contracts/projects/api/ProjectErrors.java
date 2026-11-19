package com.construction.contracts.projects.api;

import com.construction.contracts.projects.application.ProjectProblem;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = ProjectController.class)
class ProjectErrors {
    @ExceptionHandler(ProjectProblem.class)
    ProblemDetail business(ProjectProblem error) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status()), error.getMessage());
        detail.setProperty("code", error.code());
        return detail;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail version(ResponseStatusException error) {
        var detail = error.getBody();
        detail.setProperty("code", error.getStatusCode().value() == 428 ? "version_required" : "stale_version");
        return detail;
    }
}
