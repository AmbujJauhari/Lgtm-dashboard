package com.collops.provisioner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed binding for a per-environment config file (e.g. config/qa.yaml),
 * loaded via spring.config.import set in ProvisionerApplication.main().
 *
 * YAML shape:
 *   team: CollOps
 *   env: qa
 *   service_groups:
 *     app:
 *       grafana_url: ...
 *       grafana_token_env: ...
 *       datasources:
 *         mimir: ...
 *         loki:  ...
 *         tempo: ...
 *       thresholds:
 *         error_rate_warning: 0.05
 *         latency_p99_ms: 500
 */
@ConfigurationProperties
public class EnvConfig {

    private String team;
    private String env;
    private Map<String, ServiceGroupConfig> serviceGroups = new HashMap<>();

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public Map<String, ServiceGroupConfig> getServiceGroups() { return serviceGroups; }
    public void setServiceGroups(Map<String, ServiceGroupConfig> serviceGroups) { this.serviceGroups = serviceGroups; }

    public static class ServiceGroupConfig {
        private String grafanaUrl;
        private String grafanaTokenEnv;
        private DatasourceConfig datasources = new DatasourceConfig();
        private Map<String, Object> thresholds = new HashMap<>();

        public String getGrafanaUrl() { return grafanaUrl; }
        public void setGrafanaUrl(String grafanaUrl) { this.grafanaUrl = grafanaUrl; }

        public String getGrafanaTokenEnv() { return grafanaTokenEnv; }
        public void setGrafanaTokenEnv(String grafanaTokenEnv) { this.grafanaTokenEnv = grafanaTokenEnv; }

        public DatasourceConfig getDatasources() { return datasources; }
        public void setDatasources(DatasourceConfig datasources) { this.datasources = datasources; }

        public Map<String, Object> getThresholds() { return thresholds; }
        public void setThresholds(Map<String, Object> thresholds) { this.thresholds = thresholds; }
    }

    public static class DatasourceConfig {
        private String mimir = "prometheus";
        private String loki  = "loki";
        private String tempo = "tempo";

        public String getMimir() { return mimir; }
        public void setMimir(String mimir) { this.mimir = mimir; }

        public String getLoki() { return loki; }
        public void setLoki(String loki) { this.loki = loki; }

        public String getTempo() { return tempo; }
        public void setTempo(String tempo) { this.tempo = tempo; }
    }
}
