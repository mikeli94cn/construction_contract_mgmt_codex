package com.construction.contracts.projects.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.construction.contracts.identity.api.RequestIdentity;
import com.construction.contracts.projects.application.ProjectService;
import com.construction.contracts.projects.domain.Project;
import com.construction.contracts.projects.domain.Project.Role;
import com.construction.contracts.projects.domain.Project.Status;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectMvcTest {
    private final ProjectService service = mock(ProjectService.class);
    private final MockHttpSession session = new MockHttpSession();
    private final Project project = new Project(7, "SITE-A", "Site works", Status.ACTIVE, 3);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ProjectController(service)).setControllerAdvice(new ProjectErrors()).build();

    ProjectMvcTest() {
        session.setAttribute(RequestIdentity.class.getName() + ".organization", 3L);
    }

    @Test
    void detailsUseSelectedOrganizationAndReturnStringIdsAndVersion() throws Exception {
        when(service.get(3, 5, 7)).thenReturn(new ProjectService.Detail(project, true, true));

        mvc.perform(get("/api/v1/projects/7").session(session).principal(() -> "5"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"3\""))
            .andExpect(jsonPath("$.id").value("7"))
            .andExpect(jsonPath("$.version").value("3"))
            .andExpect(jsonPath("$.canManageMembers").value(true));
    }

    @Test
    void assignmentsRequireVersionAndRejectDuplicateMembers() throws Exception {
        String body = """
            {"assignments":[{"userId":"9","roles":["PROJECT_VIEWER"]}]}
            """;
        var endpoint = "/api/v1/projects/7/members";
        mvc.perform(put(endpoint).session(session).principal(() -> "5")
                .contentType("application/json").content(body))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.code").value("version_required"));

        mvc.perform(put(endpoint).session(session).principal(() -> "5")
                .header("If-Match", "\"2\"").contentType("application/json").content("""
                    {"assignments":[{"userId":"9","roles":["PROJECT_VIEWER"]},
                                    {"userId":"9","roles":["CONTRACT_EDITOR"]}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("duplicate_project_member"));
        verifyNoInteractions(service);

        Map<Long, Set<Role>> assignments = Map.of(9L, Set.of(Role.PROJECT_VIEWER));
        when(service.replaceMembers(3, 5, 7, 2, assignments))
            .thenReturn(new ProjectService.Members(project, assignments, List.of()));
        mvc.perform(put(endpoint).session(session).principal(() -> "5")
                .header("If-Match", "\"2\"").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"3\""))
            .andExpect(jsonPath("$.assignments[0].userId").value("9"));
    }
}
