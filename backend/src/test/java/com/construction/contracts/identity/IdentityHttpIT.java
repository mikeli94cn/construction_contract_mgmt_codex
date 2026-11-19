package com.construction.contracts.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.construction.contracts.ContractsApplication;
import com.construction.contracts.identity.application.IdentityService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IdentityHttpIT {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void migratedDatabaseSupportsSecureMemberLifecycle() throws Exception {
        String password = UUID.randomUUID().toString();
        try (var mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            try (var context = new SpringApplicationBuilder(ContractsApplication.class).properties(
                "server.port=0",
                "server.servlet.session.cookie.secure=false",
                "spring.datasource.url=" + mysql.getJdbcUrl(),
                "spring.datasource.username=" + mysql.getUsername(),
                "spring.datasource.password=" + mysql.getPassword(),
                "app.bootstrap.enabled=true",
                "app.bootstrap.code=SITE",
                "app.bootstrap.name=Site Operations",
                "app.bootstrap.email=admin@example.test",
                "app.bootstrap.password=" + password).run()) {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                var admin = new Browser(port);
                assertEquals(401, admin.get("/auth/me").statusCode());
                assertEquals(403, admin.send("POST", "/auth/login", login("admin@example.test", password),
                    null, false).statusCode());
                admin.signIn("admin@example.test", password);
                String orgId = body(admin.get("/auth/me")).get("activeOrganizationId").asString();
                String directory = "/organizations/" + orgId + "/members";
                assertEquals(404, admin.get("/organizations/999/members").statusCode());

                var provisioned = admin.send("POST", directory, """
                    {"email":"member@example.test","displayName":"Site Member","roles":["MEMBER"]}
                    """, null, true);
                assertEquals(201, provisioned.statusCode(), provisioned.body());
                var created = body(provisioned);
                var member = new Browser(port);
                String activation = json.writeValueAsString(java.util.Map.of(
                    "token", created.get("activationToken").asString(), "password", password));
                assertEquals(204, member.send("POST", "/auth/activation", activation, null, true).statusCode());
                assertEquals(400, member.send("POST", "/auth/activation", activation, null, true).statusCode());
                member.signIn("member@example.test", password);
                assertEquals(403, member.get(directory).statusCode());

                String memberId = created.get("member").get("userId").asString();
                String projectPath = verifyProjects(admin, member, memberId);
                context.getBean(IdentityService.class).bootstrap("OTHER", "Other organization",
                    "other@example.test", password);
                var other = new Browser(port);
                other.signIn("other@example.test", password);
                assertEquals(404, other.get(projectPath).statusCode());
                assertEquals(201, other.send("POST", "/projects", projectInput("SITE-A"), null, true).statusCode());

                String path = directory + "/" + memberId;
                String disable = "{\"enabled\":false,\"roles\":[\"MEMBER\"]}";
                assertEquals(428, admin.send("PATCH", path, disable, null, true).statusCode());
                assertEquals(200, admin.send("PATCH", path, disable, "\"0\"", true).statusCode());
                assertEquals(412, admin.send("PATCH", path, disable, "\"0\"", true).statusCode());
                assertEquals(404, member.get(directory).statusCode());
                assertEquals(404, member.get(projectPath).statusCode());
                assertEquals(204, admin.send("POST", "/auth/logout", null, null, true).statusCode());
                assertEquals(401, admin.get("/auth/me").statusCode());
            }
        }
    }

    private String verifyProjects(Browser admin, Browser member, String memberId) throws Exception {
        assertEquals(0, body(member.get("/projects")).get("total").asInt());
        assertEquals(403, member.send("POST", "/projects", projectInput("SITE-A"), null, true).statusCode());
        var created = admin.send("POST", "/projects", projectInput("site-a"), null, true);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("SITE-A", body(created).get("code").asString());
        String path = created.headers().firstValue("Location").orElseThrow().substring("/api/v1".length());
        assertEquals(409, admin.send("POST", "/projects", projectInput("SITE-A"), null, true).statusCode());
        assertEquals(404, member.get(path).statusCode());

        String roles = json.writeValueAsString(java.util.Map.of("assignments",
            java.util.List.of(java.util.Map.of("userId", memberId, "roles", java.util.List.of("PROJECT_VIEWER")))));
        assertEquals(200, admin.send("PUT", path + "/members", roles, "\"0\"", true).statusCode());
        assertEquals(200, member.get(path).statusCode());
        assertEquals(1, body(member.get("/projects")).get("total").asInt());
        assertEquals(403, member.get(path + "/members").statusCode());
        assertEquals(412, admin.send("PATCH", path, "{}", "\"0\"", true).statusCode());
        assertEquals(200, admin.send("PUT", path + "/members", "{\"assignments\":[]}", "\"1\"", true).statusCode());
        assertEquals(404, member.get(path).statusCode());
        assertEquals(200, admin.send("PUT", path + "/members", roles, "\"2\"", true).statusCode());
        return path;
    }

    private String projectInput(String code) {
        return json.writeValueAsString(java.util.Map.of("code", code, "name", "Site works"));
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String login(String email, String password) {
        return json.writeValueAsString(java.util.Map.of("email", email, "password", password));
    }

    private class Browser {
        private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private final String base;

        Browser(int port) {
            base = "http://localhost:" + port + "/api/v1";
        }

        void signIn(String email, String password) throws Exception {
            var response = send("POST", "/auth/login", login(email, password), null, true);
            assertEquals(204, response.statusCode(), response.body());
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, null, false);
        }

        HttpResponse<String> send(String method, String path, String input, String etag,
                                  boolean csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).method(method,
                input == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(input));
            if (input != null) request.header("Content-Type", "application/json");
            if (etag != null) request.header("If-Match", etag);
            if (csrf) {
                JsonNode token = body(get("/auth/csrf"));
                request.header(token.get("headerName").asString(), token.get("token").asString());
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
