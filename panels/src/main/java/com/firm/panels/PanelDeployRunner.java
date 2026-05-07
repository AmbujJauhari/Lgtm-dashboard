package com.firm.panels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Deploys a versioned set of firm-wide library panels to one or more Grafana instances.
 *
 * Usage:
 *   java -jar panels.jar deploy --config=config/qa.yaml
 *
 * The panel version (e.g. v1) comes from panel_version in the config file and is appended
 * as a suffix to each panel UID and name so multiple versions can coexist in the same
 * Grafana org without UID collisions.
 *
 * Example: uid "rm" at version v1 becomes "rm-v1" in Grafana.
 */
@Component
public class PanelDeployRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(PanelDeployRunner.class);

    private static final String MIMIR_PLACEHOLDER = "__MIMIR__";
    private static final String LOKI_PLACEHOLDER  = "__LOKI__";
    private static final String TEMPO_PLACEHOLDER = "__TEMPO__";

    @Autowired private PanelConfig config;
    @Autowired private ResourcePatternResolver resourceResolver;

    private final ObjectMapper mapper = new ObjectMapper();
    private int exitCode = 0;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> nonOptionArgs = args.getNonOptionArgs();
        if (nonOptionArgs.isEmpty() || !"deploy".equals(nonOptionArgs.get(0))) {
            return;
        }

        String version   = config.getPanelVersion();
        String folderUid = config.getFolderUid();

        if (version == null || version.isBlank()) {
            System.err.println("Config must declare 'panel_version'.");
            exitCode = 1;
            return;
        }
        if (config.getGrafanaInstances() == null || config.getGrafanaInstances().isEmpty()) {
            System.err.println("Config must declare at least one entry under 'grafana_instances'.");
            exitCode = 1;
            return;
        }

        Resource[] panels = resourceResolver.getResources("classpath:/panels/**/*.json");

        if (panels.length == 0) {
            System.err.println("No panel JSON files found under classpath:/panels/");
            exitCode = 1;
            return;
        }

        log.info("Deploying {} panel(s) at version '{}' to {} Grafana instance(s).",
                panels.length, version, config.getGrafanaInstances().size());

        String grafanaUser = System.getenv().getOrDefault("GRAFANA_USER", "admin");
        String grafanaPass = System.getenv().getOrDefault("GRAFANA_PASSWORD", "admin");

        for (PanelConfig.GrafanaInstance instance : config.getGrafanaInstances()) {
            deployToInstance(instance, panels, version, folderUid, grafanaUser, grafanaPass);
        }

        log.info("Panel deployment complete for version '{}'.", version);
    }

    private void deployToInstance(PanelConfig.GrafanaInstance instance,
                                   Resource[] panels,
                                   String version,
                                   String folderUid,
                                   String grafanaUser,
                                   String grafanaPass) throws Exception {
        String token = instance.getTokenEnv() != null ? System.getenv(instance.getTokenEnv()) : null;
        GrafanaClient client = new GrafanaClient(instance.getUrl(), token, grafanaUser, grafanaPass);

        client.ensureFolder(folderUid, config.getFolderTitle(), null);

        PanelConfig.DatasourceConfig ds = instance.getDatasources();

        for (Resource panel : panels) {
            String raw        = panel.getContentAsString(StandardCharsets.UTF_8);
            String versioned  = applyVersionSuffix(version, raw);
            String injected   = injectDatasources(versioned, ds.getMimir(), ds.getLoki(), ds.getTempo())
                                    .replace("__FOLDER_UID__", folderUid);
            client.upsertLibraryPanel(folderUid, injected);
        }

        log.info("Deployed panels to '{}' ({})", instance.getName(), instance.getUrl());
    }

    // ── UID versioning ────────────────────────────────────────────────────────

    private String applyVersionSuffix(String version, String panelJson) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(panelJson);
        String uid = root.path("uid").asText();
        if (!uid.isEmpty()) {
            root.put("uid", uid + "-" + version);
        }
        String name = root.path("name").asText();
        if (!name.isEmpty()) {
            root.put("name", name + " (" + version + ")");
        }
        return mapper.writeValueAsString(root);
    }

    // ── Datasource injection ──────────────────────────────────────────────────

    private String injectDatasources(String json, String mimirUid, String lokiUid,
                                      String tempoUid) throws Exception {
        JsonNode root = mapper.readTree(json);
        replaceUids(root, mimirUid, lokiUid, tempoUid);
        return mapper.writeValueAsString(root);
    }

    private void replaceUids(JsonNode node, String mimirUid, String lokiUid, String tempoUid) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("uid")) {
                String uid = obj.get("uid").asText();
                if (MIMIR_PLACEHOLDER.equals(uid))      obj.put("uid", mimirUid);
                else if (LOKI_PLACEHOLDER.equals(uid))  obj.put("uid", lokiUid);
                else if (TEMPO_PLACEHOLDER.equals(uid)) obj.put("uid", tempoUid);
            }
            obj.fields().forEachRemaining(e -> replaceUids(e.getValue(), mimirUid, lokiUid, tempoUid));
        } else if (node.isArray()) {
            node.forEach(child -> replaceUids(child, mimirUid, lokiUid, tempoUid));
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
