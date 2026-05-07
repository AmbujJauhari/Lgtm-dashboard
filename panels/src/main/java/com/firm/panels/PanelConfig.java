package com.firm.panels;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties
public class PanelConfig {

    private String folderUid = "firm-library-panels";
    private String folderTitle = "Firm Library Panels";
    private List<GrafanaInstance> grafanaInstances;

    public String getFolderUid() { return folderUid; }
    public void setFolderUid(String folderUid) { this.folderUid = folderUid; }

    public String getFolderTitle() { return folderTitle; }
    public void setFolderTitle(String folderTitle) { this.folderTitle = folderTitle; }

    public List<GrafanaInstance> getGrafanaInstances() { return grafanaInstances; }
    public void setGrafanaInstances(List<GrafanaInstance> grafanaInstances) { this.grafanaInstances = grafanaInstances; }

    public static class GrafanaInstance {
        private String name;
        private String url;
        private String tokenEnv;
        private DatasourceConfig datasources = new DatasourceConfig();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getTokenEnv() { return tokenEnv; }
        public void setTokenEnv(String tokenEnv) { this.tokenEnv = tokenEnv; }

        public DatasourceConfig getDatasources() { return datasources; }
        public void setDatasources(DatasourceConfig datasources) { this.datasources = datasources; }
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
