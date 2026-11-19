package com.construction.contracts.projects.api;

import com.construction.contracts.identity.api.RequestIdentity;
import com.construction.contracts.projects.application.ProjectProblem;
import com.construction.contracts.projects.application.ProjectService;
import com.construction.contracts.projects.domain.Project;
import com.construction.contracts.projects.domain.Project.Role;
import com.construction.contracts.projects.domain.Project.Status;
import com.construction.contracts.web.VersionHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {
    private static final String CODE = "[A-Za-z0-9_-]{1,40}";

    record Create(@NotNull @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 160) String name) {}
    record Update(@Pattern(regexp = CODE) String code, @Pattern(regexp = "(?s).*\\S.*") @Size(max = 160) String name,
                  Status status) {}
    record Assignment(@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String userId,
                      @NotEmpty Set<@NotNull Role> roles) {}
    record Replace(@NotNull List<@NotNull @Valid Assignment> assignments) {}
    record Summary(String id, String code, String name, Status status, String version) {}
    record Detail(String id, String code, String name, Status status, String version,
                  boolean canEdit, boolean canManageMembers) {}
    record Page(List<Summary> items, int page, int size, long total, boolean canCreate) {}
    record EligibleMember(String userId, String displayName, String email) {}
    record Members(List<Assignment> assignments, List<EligibleMember> eligibleMembers, String version) {}
    private record Scope(long org, long actor) {}

    private final ProjectService service;

    ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    Page list(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
              @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size, HttpServletRequest request) {
        Scope scope = scope(request);
        var result = service.list(scope.org(), scope.actor(), page * size, size);
        return new Page(result.items().stream().map(ProjectController::summary).toList(),
            page, size, result.total(), result.canCreate());
    }

    @PostMapping
    ResponseEntity<Detail> create(@Valid @RequestBody Create input, HttpServletRequest request) {
        Scope scope = scope(request);
        var result = service.create(scope.org(), scope.actor(), input.code(), input.name());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + result.project().id()))
            .eTag(VersionHeaders.etag(result.project().version())).body(detail(result));
    }

    @GetMapping("/{projectId}")
    ResponseEntity<Detail> get(@PathVariable long projectId, HttpServletRequest request) {
        Scope scope = scope(request);
        var result = service.get(scope.org(), scope.actor(), projectId);
        return ResponseEntity.ok().eTag(VersionHeaders.etag(result.project().version())).body(detail(result));
    }

    @PatchMapping("/{projectId}")
    ResponseEntity<Detail> update(@PathVariable long projectId, @RequestHeader(value = "If-Match", required = false) String match,
                                  @Valid @RequestBody Update input, HttpServletRequest request) {
        Scope scope = scope(request);
        var result = service.update(scope.org(), scope.actor(), projectId, VersionHeaders.requireVersion(match),
            input.code(), input.name(), input.status());
        return ResponseEntity.ok().eTag(VersionHeaders.etag(result.project().version())).body(detail(result));
    }

    @GetMapping("/{projectId}/members")
    ResponseEntity<Members> members(@PathVariable long projectId, HttpServletRequest request) {
        Scope scope = scope(request);
        return membersResponse(service.members(scope.org(), scope.actor(), projectId));
    }

    @PutMapping("/{projectId}/members")
    ResponseEntity<Members> replace(@PathVariable long projectId, @RequestHeader(value = "If-Match", required = false) String match,
                                    @Valid @RequestBody Replace input, HttpServletRequest request) {
        Scope scope = scope(request);
        Map<Long, Set<Role>> assignments = new LinkedHashMap<>();
        for (Assignment assignment : input.assignments()) {
            long userId;
            try {
                userId = Long.parseLong(assignment.userId());
            } catch (NumberFormatException failure) {
                throw new ProjectProblem(400, "invalid_project_member", "Invalid member ID");
            }
            if (assignments.putIfAbsent(userId, assignment.roles()) != null) {
                throw new ProjectProblem(400, "duplicate_project_member", "Provide each member once");
            }
        }
        return membersResponse(service.replaceMembers(scope.org(), scope.actor(), projectId,
            VersionHeaders.requireVersion(match), assignments));
    }

    private static Scope scope(HttpServletRequest request) {
        return new Scope(RequestIdentity.selectedOrganization(request), RequestIdentity.userId(request.getUserPrincipal()));
    }

    private static Summary summary(Project project) {
        return new Summary(Long.toString(project.id()), project.code(), project.name(), project.status(),
            Long.toString(project.version()));
    }

    private static Detail detail(ProjectService.Detail detail) {
        Summary project = summary(detail.project());
        return new Detail(project.id(), project.code(), project.name(), project.status(), project.version(),
            detail.canEdit(), detail.canManageMembers());
    }

    private static ResponseEntity<Members> membersResponse(ProjectService.Members result) {
        var assignments = result.assignments().entrySet().stream()
            .map(entry -> new Assignment(Long.toString(entry.getKey()), entry.getValue())).toList();
        var eligible = result.eligible().stream().map(member ->
            new EligibleMember(Long.toString(member.userId()), member.displayName(), member.email())).toList();
        return ResponseEntity.ok().eTag(VersionHeaders.etag(result.project().version()))
            .body(new Members(assignments, eligible, Long.toString(result.project().version())));
    }
}
