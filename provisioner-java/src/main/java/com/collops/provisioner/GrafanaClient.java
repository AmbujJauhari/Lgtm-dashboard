package com.collops.provisioner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class GrafanaClient {

    private static final Logger log = LoggerFactory.getLogger(GrafanaClient.class);

    private final String baseUrl;
    private final String authHeader;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GrafanaClient(String baseUrl, String token, String user, String password) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();

        if (token != null && !token.isBlank()) {
            this.authHeader = "Bearer " + token;
        } else {
            String credentials = user + ":" + password;
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Ensure a folder exists; creates it if absent.
     * @param parentUid UID of the parent folder, or null for a root-level folder.
     */
    public String ensureFolder(String uid, String title, String parentUid) throws Exception {
        HttpRequest get = request("GET", "/api/folders/" + uid, null);
        HttpResponse<String> res = http.send(get, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 200) {
            log.info("Folder '{}' already exists", uid);
            return uid;
        }

        if (res.statusCode() != 404) {
            throw new GrafanaException(res.statusCode(), res.body());
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("uid", uid);
        body.put("title", title);
        if (parentUid != null) {
            body.put("parentUid", parentUid);
        }

        HttpRequest post = request("POST", "/api/folders", mapper.writeValueAsString(body));
        HttpResponse<String> createRes = http.send(post, HttpResponse.BodyHandlers.ofString());

        if (createRes.statusCode() != 200) {
            throw new GrafanaException(createRes.statusCode(), createRes.body());
        }

        log.info("Created folder '{}'{}", uid, parentUid != null ? " under '" + parentUid + "'" : "");
        return uid;
    }

    /** Upsert a library panel. Uses uid-lookup first; falls back to create. */
    public void upsertLibraryPanel(String folderUid, String panelJson) throws Exception {
        JsonNode panelNode = mapper.readTree(panelJson);
        String uid = panelNode.path("uid").asText();
        String name = panelNode.path("name").asText();

        HttpRequest get = request("GET", "/api/library-elements/" + uid, null);
        HttpResponse<String> res = http.send(get, HttpResponse.BodyHandlers.ofString());

        ObjectNode body = mapper.createObjectNode();
        body.put("folderUid", folderUid);
        body.put("name", name);
        body.put("kind", 1);
        body.set("model", panelNode.path("model"));

        if (res.statusCode() == 200) {
            JsonNode existing = mapper.readTree(res.body()).path("result");
            int version = existing.path("version").asInt(1);
            body.put("version", version);

            HttpRequest patch = request("PATCH", "/api/library-elements/" + uid,
                    mapper.writeValueAsString(body));
            HttpResponse<String> patchRes = http.send(patch, HttpResponse.BodyHandlers.ofString());
            if (patchRes.statusCode() != 200) {
                throw new GrafanaException(patchRes.statusCode(), patchRes.body());
            }
            String storedUid = mapper.readTree(patchRes.body()).path("result").path("uid").asText();
            log.info("Updated library panel '{}' uid={}", name, storedUid);
        } else if (res.statusCode() == 404) {
            body.put("uid", uid);
            HttpRequest post = request("POST", "/api/library-elements",
                    mapper.writeValueAsString(body));
            HttpResponse<String> postRes = http.send(post, HttpResponse.BodyHandlers.ofString());
            if (postRes.statusCode() != 200) {
                throw new GrafanaException(postRes.statusCode(), postRes.body());
            }
            String storedUid = mapper.readTree(postRes.body()).path("result").path("uid").asText();
            log.info("Created library panel '{}' uid={}", name, storedUid);
        } else {
            throw new GrafanaException(res.statusCode(), res.body());
        }
    }

    /** Upsert a dashboard into the given folder. */
    public void upsertDashboard(String folderUid, String dashboardJson) throws Exception {
        JsonNode dash = mapper.readTree(dashboardJson);
        String title = dash.path("title").asText("<unknown>");

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("dashboard", dash);
        wrapper.put("folderUid", folderUid);
        wrapper.put("overwrite", true);
        wrapper.put("message", "provisioned by collops-provisioner");

        HttpRequest post = request("POST", "/api/dashboards/db", mapper.writeValueAsString(wrapper));
        HttpResponse<String> res = http.send(post, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            throw new GrafanaException(res.statusCode(), res.body());
        }

        log.info("Upserted dashboard '{}'", title);
    }

    /**
     * Resolve a dashboard UID to its numeric Grafana id.
     * Returns -1 if the dashboard does not exist.
     */
    public int resolveDashboardId(String uid) throws Exception {
        HttpRequest get = request("GET", "/api/dashboards/uid/" + uid, null);
        HttpResponse<String> res = http.send(get, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return -1;
        if (res.statusCode() != 200) throw new GrafanaException(res.statusCode(), res.body());
        return mapper.readTree(res.body()).path("dashboard").path("id").asInt(-1);
    }

    /**
     * Roll back a dashboard to a specific version.
     * Use resolveDashboardId() to obtain the numeric id from a UID first.
     */
    public void rollback(int dashboardId, int version) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("version", version);
        HttpRequest post = request("POST",
                "/api/dashboards/id/" + dashboardId + "/restore",
                mapper.writeValueAsString(body));
        HttpResponse<String> res = http.send(post, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new GrafanaException(res.statusCode(), res.body());
        log.info("Rolled back dashboard id={} to version {}", dashboardId, version);
    }

    private HttpRequest request(String method, String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }

        return builder.build();
    }
}
