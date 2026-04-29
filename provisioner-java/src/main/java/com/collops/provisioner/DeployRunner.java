package com.collops.provisioner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the `deploy` command.
 *
 * Usage:
 *   java -jar provisioner.jar deploy --config=config/qa.yaml
 *
 * Provisions every service_group defined in the config file into the environment
 * declared by that same file. Each service group gets its own folder subtree:
 *   {team} → {service_group} → {env}
 */
@Component
@Order(1)
public class DeployRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeployRunner.class);

    private static final List<String> DASHBOARD_TEMPLATES = List.of(
            "overview.ftl",
            "service_overview.ftl",
            "investigation.ftl"
    );

    private static final List<String> LIBRARY_PANEL_FILES = List.of(
            "component/red_metrics.json",
            "component/outbound_http.json",
            "component/jvm_heap_gc.json",
            "component/jvm_gc.json",
            "component/thread_count.json",
            "component/log_stream.json"
    );

    private static final String MIMIR_PLACEHOLDER        = "__MIMIR__";
    private static final String LOKI_PLACEHOLDER         = "__LOKI__";
    private static final String TEMPO_PLACEHOLDER        = "__TEMPO__";
    private static final String APP_SELECTOR_PLACEHOLDER = "__APP_SELECTOR__";

    @Autowired private Configuration freemarkerConfig;
    @Autowired private EnvConfig envConfig;

    private final ObjectMapper mapper = new ObjectMapper();
    private int exitCode = 0;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> nonOptionArgs = args.getNonOptionArgs();
        if (nonOptionArgs.isEmpty() || !"deploy".equals(nonOptionArgs.get(0))) {
            return;
        }

        String team = envConfig.getTeam();
        String env  = envConfig.getEnv();
        Map<String, EnvConfig.ServiceGroupConfig> serviceGroups = envConfig.getServiceGroups();

        if (team == null || env == null) {
            System.err.println("Config file must declare 'team' and 'env' at the top level.");
            exitCode = 1;
            return;
        }
        if (serviceGroups == null || serviceGroups.isEmpty()) {
            System.err.println("No service_groups defined in config file.");
            exitCode = 1;
            return;
        }

        // Team folder is shared across all service groups — create it once.
        String teamFolderUid = slug(team);
        // Each service group may have its own Grafana instance, so we create one client per group.
        // For the team folder we use the first service group's connection (any will do for a shared instance).
        EnvConfig.ServiceGroupConfig first = serviceGroups.values().iterator().next();
        String firstToken = resolveToken(first);
        String grafanaUser = System.getenv().getOrDefault("GRAFANA_USER", "admin");
        String grafanaPass = System.getenv().getOrDefault("GRAFANA_PASSWORD", "admin");
        new GrafanaClient(first.getGrafanaUrl(), firstToken, grafanaUser, grafanaPass)
                .ensureFolder(teamFolderUid, team, null);

        for (Map.Entry<String, EnvConfig.ServiceGroupConfig> entry : serviceGroups.entrySet()) {
            String sgName = entry.getKey();
            EnvConfig.ServiceGroupConfig sg = entry.getValue();
            deployServiceGroup(team, teamFolderUid, sgName, env, sg, grafanaUser, grafanaPass);
        }

        log.info("Provisioning complete for env='{}'.", env);
    }

    private void deployServiceGroup(String team, String teamFolderUid, String sgName, String env,
                                     EnvConfig.ServiceGroupConfig sg,
                                     String grafanaUser, String grafanaPass) throws Exception {
        String teamSlug = slug(team);
        String sgSlug   = slug(sgName);
        String envSlug  = slug(env);

        String sgFolderUid  = teamSlug + "-" + sgSlug;
        String envFolderUid = teamSlug + "-" + sgSlug + "-" + envSlug;

        String appSelector = String.format("team=\"%s\",service_group=\"%s\"", team, sgName);
        String appSelectorJsonEscaped = appSelector.replace("\"", "\\\"");

        String mimirUid = sg.getDatasources().getMimir();
        String lokiUid  = sg.getDatasources().getLoki();
        String tempoUid = sg.getDatasources().getTempo();

        log.info("Deploying service_group='{}' env='{}' → {}/{}/{}", sgName, env,
                teamFolderUid, sgFolderUid, envFolderUid);

        GrafanaClient client = new GrafanaClient(sg.getGrafanaUrl(), resolveToken(sg), grafanaUser, grafanaPass);

        client.ensureFolder(sgFolderUid,  sgName, teamFolderUid);
        client.ensureFolder(envFolderUid, env,    sgFolderUid);

        for (String panelFile : LIBRARY_PANEL_FILES) {
            Path path = Path.of("library_panels", panelFile);
            String raw        = Files.readString(path);
            String namespaced = namespacePanelUid(envFolderUid, raw);
            String json       = injectDatasource(namespaced, mimirUid, lokiUid, tempoUid, appSelectorJsonEscaped);
            client.upsertLibraryPanel(envFolderUid, json);
        }

        Map<String, Object> model = new HashMap<>();
        model.put("folder_uid",   envFolderUid);
        model.put("app_selector", appSelectorJsonEscaped);
        model.put("mimir_uid",    mimirUid);
        model.put("loki_uid",     lokiUid);
        model.put("tempo_uid",    tempoUid);
        model.put("thresholds",   sg.getThresholds());

        for (String templateName : DASHBOARD_TEMPLATES) {
            String json = renderTemplate(templateName, model);
            client.upsertDashboard(envFolderUid, json);
        }
    }

    // ── Template rendering ────────────────────────────────────────────────────

    private String renderTemplate(String templateName, Map<String, Object> model) throws Exception {
        Template template = freemarkerConfig.getTemplate(templateName);
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

    // ── Library panel UID namespacing ─────────────────────────────────────────

    private String namespacePanelUid(String prefix, String panelJson) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(panelJson);
        String uid = root.path("uid").asText();
        if (!uid.isEmpty()) {
            root.put("uid", prefix + "-" + uid);
        }
        return mapper.writeValueAsString(root);
    }

    // ── Library panel datasource + selector injection ─────────────────────────

    private String injectDatasource(String json, String mimirUid, String lokiUid,
                                     String tempoUid, String appSelectorJsonEscaped) throws Exception {
        JsonNode root = mapper.readTree(json);
        replaceUids(root, mimirUid, lokiUid, tempoUid);
        return mapper.writeValueAsString(root)
                     .replace(APP_SELECTOR_PLACEHOLDER, appSelectorJsonEscaped);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveToken(EnvConfig.ServiceGroupConfig sg) {
        return sg.getGrafanaTokenEnv() != null ? System.getenv(sg.getGrafanaTokenEnv()) : null;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
