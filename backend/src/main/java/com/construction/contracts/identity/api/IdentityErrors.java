package com.construction.contracts.identity.api;

import com.construction.contracts.identity.application.IdentityProblem;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import java.util.Map;

@RestControllerAdvice
class IdentityErrors {
    @ExceptionHandler(IdentityProblem.class)
    ProblemDetail business(IdentityProblem error) {
        return problem(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail authentication() {
        return problem(401, "invalid_credentials", "Email or password is incorrect");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        return problem(409, "identity_conflict", "Identity data conflicts with an existing record");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException error) {
        var problem = problem(400, "validation_failed", "Check the submitted fields");
        problem.setProperty("errors", error.getBindingResult().getFieldErrors().stream()
            .map(field -> Map.of("field", field.getField(), "message", String.valueOf(field.getDefaultMessage())))
            .toList());
        return problem;
    }

    private static ProblemDetail problem(int status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setProperty("code", code);
        return problem;
    }
}
