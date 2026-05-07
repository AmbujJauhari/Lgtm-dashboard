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

    public void upsertDashboard(String folderUid, String dashboardJson) throws Exception {
        JsonNode dash = mapper.readTree(dashboardJson);
        String title = dash.path("title").asText("<unknown>");

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("dashboard", dash);
        wrapper.put("folderUid", folderUid);
        wrapper.put("overwrite", true);

        HttpRequest post = request("POST", "/api/dashboards/db", mapper.writeValueAsString(wrapper));
        HttpResponse<String> res = http.send(post, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new GrafanaException(res.statusCode(), res.body());
        }

        log.info("Upserted dashboard '{}'", title);
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
