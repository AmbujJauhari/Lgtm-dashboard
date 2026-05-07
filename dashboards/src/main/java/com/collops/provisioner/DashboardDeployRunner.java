package com.collops.provisioner;

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the `deploy` command.
 *
 * Usage:
 *   java -jar dashboards.jar deploy --config=config/qa.yaml
 *
 * Renders the three CollOps Freemarker dashboard templates and upserts them into Grafana.
 * Library panels must already be deployed (run the panels module first).
 * Panel UIDs referenced in templates follow the {uid}-{panels_version} suffix convention.
 *
 * The deployer version is stamped into each dashboard's tags and into Grafana's version
 * history message, making every Grafana dashboard version traceable to the deployer release.
 */
@Component
@Order(1)
public class DashboardDeployRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DashboardDeployRunner.class);

    private static final List<String> DASHBOARD_TEMPLATES = List.of(
            "overview.ftl",
            "service_overview.ftl",
            "investigation.ftl"
    );

    @Autowired private Configuration freemarkerConfig;
    @Autowired private EnvConfig envConfig;

    @Value("${app.version:unknown}")
    private String appVersion;

    private int exitCode = 0;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> nonOptionArgs = args.getNonOptionArgs();
        if (nonOptionArgs.isEmpty() || !"deploy".equals(nonOptionArgs.get(0))) {
            return;
        }

        String team          = envConfig.getTeam();
        String panelsVersion = envConfig.getPanelsVersion();
        Map<String, EnvConfig.ServiceGroupConfig> serviceGroups = envConfig.getServiceGroups();

        if (team == null) {
            System.err.println("Config file must declare 'team' at the top level.");
            exitCode = 1;
            return;
        }
        if (panelsVersion == null || panelsVersion.isBlank()) {
            System.err.println("Config file must declare 'panels_version'.");
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
            deployServiceGroup(team, teamFolderUid, entry.getKey(), entry.getValue(),
                    panelsVersion, grafanaUser, grafanaPass);
        }

        log.info("Dashboard provisioning complete for team='{}' panels='{}'.", team, panelsVersion);
    }

    private void deployServiceGroup(String team, String teamFolderUid, String sgName,
                                     EnvConfig.ServiceGroupConfig sg,
                                     String panelsVersion,
                                     String grafanaUser, String grafanaPass) throws Exception {
        validateUidSafe(sgName,               "service_group key '" + sgName + "'");
        validateUidSafe(sg.getCmdbReference(), "cmdb_reference");
        validateUidSafe(sg.getOpEnvironment(), "op_environment");

        String cmdbRef       = sg.getCmdbReference();
        String sgFolderUid   = team + "-" + sgName;
        String cmdbFolderUid = team + "-" + sgName + "-" + cmdbRef;
        validateUidLength(cmdbFolderUid, "Combined folder UID (team-serviceGroup-cmdbReference)");

        String appSelector            = String.format("cmdbReference=\"%s\"", cmdbRef);
        String appSelectorJsonEscaped = appSelector.replace("\"", "\\\"");

        log.info("Deploying service_group='{}' cmdbReference='{}' opEnvironment='{}' panels='{}'",
                sgName, cmdbRef, sg.getOpEnvironment(), panelsVersion);

        GrafanaClient client = new GrafanaClient(sg.getGrafanaUrl(), resolveToken(sg), grafanaUser, grafanaPass);
        client.ensureFolder(sgFolderUid,   sgName,  teamFolderUid);
        client.ensureFolder(cmdbFolderUid, cmdbRef, sgFolderUid);

        Map<String, Object> model = new HashMap<>();
        model.put("folder_uid",     cmdbFolderUid);
        model.put("team",           team);
        model.put("team_tag",       team.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        model.put("service_group",  sgName);
        model.put("cmdb_ref",       cmdbRef);
        model.put("app_selector",   appSelectorJsonEscaped);
        model.put("mimir_uid",      sg.getDatasources().getMimir());
        model.put("loki_uid",       sg.getDatasources().getLoki());
        model.put("tempo_uid",      sg.getDatasources().getTempo());
        model.put("thresholds",     sg.getThresholds());
        model.put("panels_version", panelsVersion);

        String deployMessage = String.format("panels-%s dashboards-%s", panelsVersion, appVersion);

        for (String templateName : DASHBOARD_TEMPLATES) {
            String json = renderTemplate(templateName, model);
            client.upsertDashboard(cmdbFolderUid, json, deployMessage);
        }
    }

    private String renderTemplate(String templateName, Map<String, Object> model) throws Exception {
        Template template = freemarkerConfig.getTemplate(templateName);
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

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

    private String resolveToken(EnvConfig.ServiceGroupConfig sg) {
        return sg.getGrafanaTokenEnv() != null ? System.getenv(sg.getGrafanaTokenEnv()) : null;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
