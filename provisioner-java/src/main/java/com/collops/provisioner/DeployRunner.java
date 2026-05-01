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
 * Folder hierarchy: {team} → {service_group} → {cmdbReference}
 * Panel/dashboard UIDs are namespaced by cmdbReference (AT code), which is unique per deployment.
 *
 * All YAML values are used verbatim — no silent transformation. Values must contain only
 * letters, digits, hyphens, underscores or dots (Grafana UID character set).
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
            "component/log_stream.json",
            "aggregate/health_heatmap.json",
            "aggregate/exception_count.json",
            "aggregate/top_slowest.json",
            "aggregate/error_budget.json",
            "aggregate/log_volume.json"
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
        Map<String, EnvConfig.ServiceGroupConfig> serviceGroups = envConfig.getServiceGroups();

        if (team == null) {
            System.err.println("Config file must declare 'team' at the top level.");
            exitCode = 1;
            return;
        }
        if (serviceGroups == null || serviceGroups.isEmpty()) {
            System.err.println("No service_groups defined in config file.");
            exitCode = 1;
            return;
        }

        validateUidSafe(team, "team");

        String teamFolderUid = team;
        EnvConfig.ServiceGroupConfig first = serviceGroups.values().iterator().next();
        String grafanaUser = System.getenv().getOrDefault("GRAFANA_USER", "admin");
        String grafanaPass = System.getenv().getOrDefault("GRAFANA_PASSWORD", "admin");
        new GrafanaClient(first.getGrafanaUrl(), resolveToken(first), grafanaUser, grafanaPass)
                .ensureFolder(teamFolderUid, team, null);

        for (Map.Entry<String, EnvConfig.ServiceGroupConfig> entry : serviceGroups.entrySet()) {
            String sgName = entry.getKey();
            EnvConfig.ServiceGroupConfig sg = entry.getValue();
            deployServiceGroup(team, teamFolderUid, sgName, sg, grafanaUser, grafanaPass);
        }

        log.info("Provisioning complete for team='{}'.", team);
    }

    private void deployServiceGroup(String team, String teamFolderUid, String sgName,
                                     EnvConfig.ServiceGroupConfig sg,
                                     String grafanaUser, String grafanaPass) throws Exception {
        validateUidSafe(sgName,                  "service_group key '" + sgName + "'");
        validateUidSafe(sg.getCmdbReference(),   "cmdb_reference");
        validateUidSafe(sg.getOpEnvironment(),   "op_environment");

        String cmdbRef = sg.getCmdbReference();

        String sgFolderUid   = team + "-" + sgName;
        String cmdbFolderUid = team + "-" + sgName + "-" + cmdbRef;
        validateUidLength(cmdbFolderUid, "Combined folder UID (team-serviceGroup-cmdbReference)");

        String appSelector = String.format("cmdbReference=\"%s\"", cmdbRef);
        String appSelectorJsonEscaped = appSelector.replace("\"", "\\\"");

        String mimirUid = sg.getDatasources().getMimir();
        String lokiUid  = sg.getDatasources().getLoki();
        String tempoUid = sg.getDatasources().getTempo();

        log.info("Deploying service_group='{}' cmdbReference='{}' opEnvironment='{}' → {}/{}/{}",
                sgName, cmdbRef, sg.getOpEnvironment(),
                teamFolderUid, sgFolderUid, cmdbFolderUid);

        GrafanaClient client = new GrafanaClient(sg.getGrafanaUrl(), resolveToken(sg), grafanaUser, grafanaPass);

        client.ensureFolder(sgFolderUid,   sgName,  teamFolderUid);
        client.ensureFolder(cmdbFolderUid, cmdbRef, sgFolderUid);

        for (String panelFile : LIBRARY_PANEL_FILES) {
            Path path = Path.of("library_panels", panelFile);
            String raw        = Files.readString(path);
            String namespaced = namespacePanelUid(cmdbRef, raw);
            String json       = injectDatasource(namespaced, mimirUid, lokiUid, tempoUid, appSelectorJsonEscaped)
                                    .replace("__FOLDER_UID__", cmdbRef);
            client.upsertLibraryPanel(cmdbFolderUid, json);
        }

        // Library element writes are occasionally not visible to the dashboard save
        // endpoint immediately on this Grafana build; a brief pause avoids the race.
        Thread.sleep(1000);

        Map<String, Object> model = new HashMap<>();
        model.put("folder_uid",    cmdbFolderUid);
        model.put("team",          team);
        model.put("team_tag",      team.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        model.put("service_group", sgName);
        model.put("cmdb_ref",      cmdbRef);
        model.put("app_selector",  appSelectorJsonEscaped);
        model.put("mimir_uid",     mimirUid);
        model.put("loki_uid",      lokiUid);
        model.put("tempo_uid",     tempoUid);
        model.put("thresholds",    sg.getThresholds());

        for (String templateName : DASHBOARD_TEMPLATES) {
            String json = renderTemplate(templateName, model);
            client.upsertDashboard(cmdbFolderUid, json);
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

    // ── Validation ───────────────────────────────────────────────────────────

    private static void validateUidSafe(String value, String fieldName) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(fieldName + " must not be blank");
        if (!value.matches("[a-zA-Z0-9\\-_.]+"))
            throw new IllegalArgumentException(
                fieldName + " value '" + value + "' contains characters invalid for Grafana UIDs. " +
                "Allowed: letters, digits, hyphens, underscores, dots.");
        validateUidLength(value, fieldName);
    }

    private static void validateUidLength(String value, String fieldName) {
        if (value.length() > 40)
            throw new IllegalArgumentException(
                fieldName + " value '" + value + "' is " + value.length() +
                " characters — exceeds the 40-character Grafana UID limit.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveToken(EnvConfig.ServiceGroupConfig sg) {
        return sg.getGrafanaTokenEnv() != null ? System.getenv(sg.getGrafanaTokenEnv()) : null;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
