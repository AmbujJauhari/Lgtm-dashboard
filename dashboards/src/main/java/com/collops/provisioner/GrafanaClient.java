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

    /**
     * Upsert a dashboard. The deployer version is recorded in the Grafana version history
     * message and in the dashboard tags so every Grafana version maps back to the deployer.
     */
    public void upsertDashboard(String folderUid, String dashboardJson, String deployMessage) throws Exception {
        JsonNode dash = mapper.readTree(dashboardJson);
        String title = dash.path("title").asText("<unknown>");

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("dashboard", dash);
        wrapper.put("folderUid", folderUid);
        wrapper.put("overwrite", true);
        wrapper.put("message", deployMessage);

        HttpRequest post = request("POST", "/api/dashboards/db", mapper.writeValueAsString(wrapper));
        HttpResponse<String> res = http.send(post, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new GrafanaException(res.statusCode(), res.body());
        }

        log.info("Upserted dashboard '{}'", title);
    }

    public int resolveDashboardId(String uid) throws Exception {
        HttpRequest get = request("GET", "/api/dashboards/uid/" + uid, null);
        HttpResponse<String> res = http.send(get, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return -1;
        if (res.statusCode() != 200) throw new GrafanaException(res.statusCode(), res.body());
        return mapper.readTree(res.body()).path("dashboard").path("id").asInt(-1);
    }

    /**
     * Scans the version history of a dashboard and returns the Grafana version number whose
     * message field contains "dashboards-{deployerVersion}". Returns -1 if not found.
     */
    public int findGrafanaVersionForDeployer(int dashboardId, String deployerVersion) throws Exception {
        String marker = "dashboards-" + deployerVersion;
        int limit = 100;
        int start = 0;

        while (true) {
            HttpRequest get = request("GET",
                    "/api/dashboards/id/" + dashboardId + "/versions?limit=" + limit + "&start=" + start,
                    null);
            HttpResponse<String> res = http.send(get, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new GrafanaException(res.statusCode(), res.body());

            JsonNode versions = mapper.readTree(res.body());
            if (!versions.isArray() || versions.isEmpty()) break;

            for (JsonNode v : versions) {
                String message = v.path("message").asText("");
                if (message.contains(marker)) {
                    return v.path("version").asInt(-1);
                }
            }

            if (versions.size() < limit) break;
            start += limit;
        }

        return -1;
    }

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
