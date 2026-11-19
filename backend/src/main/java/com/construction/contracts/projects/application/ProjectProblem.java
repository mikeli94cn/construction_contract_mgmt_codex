package com.construction.contracts.projects.application;

public class ProjectProblem extends RuntimeException {
    private final int status;
    private final String code;

    public ProjectProblem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
