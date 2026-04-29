{
  "uid": "[=folder_uid]-service-overview",
  "title": "Service Overview",
  "description": "Tier 2 — component dashboard. Choose a service from the dropdown. JVM and Server rows start collapsed — expand as needed.",
  "tags": ["collops", "component", "tier2"],
  "timezone": "browser",
  "refresh": "30s",
  "schemaVersion": 39,
  "time": { "from": "now-1h", "to": "now" },
  "timepicker": {},
  "links": [
    {
      "title": "Investigate",
      "url": "/d/[=folder_uid]-investigation?var-service_name=$service_name&${__url_time_range}",
      "type": "link",
      "icon": "external link",
      "tooltip": "Open Investigation dashboard — correlate logs, metrics and traces",
      "targetBlank": false,
      "includeVars": false,
      "keepTime": true
    },
    {
      "title": "CollOps Overview",
      "url": "/d/[=folder_uid]-overview",
      "type": "link",
      "icon": "arrow-left",
      "tooltip": "Back to fleet overview",
      "targetBlank": false,
      "includeVars": false,
      "keepTime": true
    }
  ],
  "templating": {
    "list": [
      {
        "type": "query",
        "name": "service_name",
        "label": "Service",
        "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
        "definition": "label_values({[=app_selector]}, service_name)",
        "query": {
          "query": "label_values({[=app_selector]}, service_name)",
          "refId": "StandardVariableQuery"
        },
        "refresh": 2,
        "sort": 1,
        "current": {},
        "hide": 0,
        "includeAll": false,
        "multi": false
      },
      {
        "type": "query",
        "name": "instance",
        "label": "Instance",
        "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
        "definition": "label_values({[=app_selector],service_name=\"$service_name\"}, instance)",
        "query": {
          "query": "label_values({[=app_selector],service_name=\"$service_name\"}, instance)",
          "refId": "StandardVariableQuery"
        },
        "refresh": 2,
        "sort": 1,
        "current": {},
        "hide": 0,
        "includeAll": true,
        "allValue": ".*",
        "multi": false
      },
      {
        "type": "query",
        "name": "host",
        "label": "Host",
        "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
        "definition": "label_values({[=app_selector],service_name=\"$service_name\"}, host_name)",
        "query": {
          "query": "label_values({[=app_selector],service_name=\"$service_name\"}, host_name)",
          "refId": "StandardVariableQuery"
        },
        "refresh": 2,
        "sort": 1,
        "current": {},
        "hide": 0,
        "includeAll": false,
        "multi": false
      }
    ]
  },
  "panels": [
    {
      "id": 10,
      "type": "row",
      "title": "Inbound Traffic",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 0, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 1,
      "gridPos": { "x": 0, "y": 1, "w": 24, "h": 9 },
      "type": "timeseries",
      "title": "[Component] RED metrics",
      "libraryPanel": { "uid": "collops-cmp-red-metrics", "name": "[Component] RED metrics" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
    },
    {
      "id": 11,
      "type": "row",
      "title": "Outbound Dependencies",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 10, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 2,
      "gridPos": { "x": 0, "y": 11, "w": 24, "h": 9 },
      "type": "timeseries",
      "title": "[Component] Outbound HTTP calls",
      "libraryPanel": { "uid": "collops-cmp-outbound-http", "name": "[Component] Outbound HTTP calls" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
    },
    {
      "id": 12,
      "type": "row",
      "title": "JVM",
      "collapsed": true,
      "gridPos": { "x": 0, "y": 20, "w": 24, "h": 1 },
      "panels": [
        {
          "id": 3,
          "gridPos": { "x": 0, "y": 21, "w": 16, "h": 9 },
          "type": "timeseries",
          "title": "[Component] JVM memory pools",
          "libraryPanel": { "uid": "collops-cmp-jvm-heap-gc", "name": "[Component] JVM memory pools" },
          "fieldConfig": { "defaults": {}, "overrides": [] },
          "options": {}
        },
        {
          "id": 4,
          "gridPos": { "x": 16, "y": 21, "w": 8, "h": 9 },
          "type": "timeseries",
          "title": "[Component] JVM GC",
          "libraryPanel": { "uid": "collops-cmp-jvm-gc", "name": "[Component] JVM GC" },
          "fieldConfig": { "defaults": {}, "overrides": [] },
          "options": {}
        },
        {
          "id": 5,
          "gridPos": { "x": 0, "y": 30, "w": 24, "h": 8 },
          "type": "timeseries",
          "title": "[Component] CPU & threads",
          "libraryPanel": { "uid": "collops-cmp-thread-count", "name": "[Component] CPU & threads" },
          "fieldConfig": { "defaults": {}, "overrides": [] },
          "options": {}
        }
      ]
    },
    {
      "id": 13,
      "type": "row",
      "title": "Server / Infrastructure",
      "collapsed": true,
      "gridPos": { "x": 0, "y": 21, "w": 24, "h": 1 },
      "panels": [
        {
          "id": 14,
          "gridPos": { "x": 0, "y": 22, "w": 12, "h": 8 },
          "type": "timeseries",
          "title": "System CPU",
          "description": "CPU time by state for the host running this service. iowait indicates disk bottlenecks; system indicates kernel overhead.",
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "targets": [
            {
              "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
              "expr": "sum by (state) (rate(system_cpu_time_seconds_total{host_name=~\"$host\",state!=\"idle\"}[$__rate_interval]))",
              "legendFormat": "{{state}}",
              "refId": "A"
            }
          ],
          "options": {
            "tooltip": { "mode": "multi", "sort": "desc" },
            "legend": { "displayMode": "list", "placement": "bottom" }
          },
          "fieldConfig": {
            "defaults": {
              "unit": "percentunit",
              "color": { "mode": "palette-classic" },
              "custom": { "lineWidth": 2, "fillOpacity": 20, "showPoints": "never", "stacking": { "group": "A", "mode": "normal" } }
            },
            "overrides": []
          }
        },
        {
          "id": 15,
          "gridPos": { "x": 12, "y": 22, "w": 12, "h": 8 },
          "type": "timeseries",
          "title": "Memory usage",
          "description": "OS memory breakdown. 'used' is actively allocated; 'cached' is reclaimable by the kernel on demand.",
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "targets": [
            {
              "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
              "expr": "system_memory_usage_bytes{host_name=~\"$host\"}",
              "legendFormat": "{{state}}",
              "refId": "A"
            }
          ],
          "options": {
            "tooltip": { "mode": "multi", "sort": "desc" },
            "legend": { "displayMode": "list", "placement": "bottom" }
          },
          "fieldConfig": {
            "defaults": {
              "unit": "bytes",
              "color": { "mode": "palette-classic" },
              "custom": { "lineWidth": 2, "fillOpacity": 10, "showPoints": "never" }
            },
            "overrides": []
          }
        },
        {
          "id": 16,
          "gridPos": { "x": 0, "y": 30, "w": 8, "h": 8 },
          "type": "timeseries",
          "title": "Swap usage",
          "description": "Swap activity indicates memory pressure. Sustained swap usage alongside GC pressure often points to heap sizing issues.",
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "targets": [
            {
              "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
              "expr": "system_swap_usage_bytes{host_name=~\"$host\"}",
              "legendFormat": "{{state}}",
              "refId": "A"
            }
          ],
          "options": {
            "tooltip": { "mode": "multi", "sort": "none" },
            "legend": { "displayMode": "list", "placement": "bottom" }
          },
          "fieldConfig": {
            "defaults": {
              "unit": "bytes",
              "color": { "mode": "palette-classic" },
              "custom": { "lineWidth": 2, "fillOpacity": 10, "showPoints": "never" }
            },
            "overrides": []
          }
        },
        {
          "id": 17,
          "gridPos": { "x": 8, "y": 30, "w": 8, "h": 8 },
          "type": "timeseries",
          "title": "Disk I/O",
          "description": "Bytes read/written per second per device. Spikes correlate with GC write activity or log rotation.",
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "targets": [
            {
              "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
              "expr": "rate(system_disk_io_bytes_total{host_name=~\"$host\"}[$__rate_interval])",
              "legendFormat": "{{device}} {{direction}}",
              "refId": "A"
            }
          ],
          "options": {
            "tooltip": { "mode": "multi", "sort": "desc" },
            "legend": { "displayMode": "list", "placement": "bottom" }
          },
          "fieldConfig": {
            "defaults": {
              "unit": "Bps",
              "color": { "mode": "palette-classic" },
              "custom": { "lineWidth": 2, "fillOpacity": 8, "showPoints": "never" }
            },
            "overrides": []
          }
        },
        {
          "id": 18,
          "gridPos": { "x": 16, "y": 30, "w": 8, "h": 8 },
          "type": "timeseries",
          "title": "Network I/O",
          "description": "Bytes received/transmitted per second per interface.",
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "targets": [
            {
              "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
              "expr": "rate(system_network_io_bytes_total{host_name=~\"$host\"}[$__rate_interval])",
              "legendFormat": "{{interface}} {{direction}}",
              "refId": "A"
            }
          ],
          "options": {
            "tooltip": { "mode": "multi", "sort": "desc" },
            "legend": { "displayMode": "list", "placement": "bottom" }
          },
          "fieldConfig": {
            "defaults": {
              "unit": "Bps",
              "color": { "mode": "palette-classic" },
              "custom": { "lineWidth": 2, "fillOpacity": 8, "showPoints": "never" }
            },
            "overrides": []
          }
        }
      ]
    },
    {
      "id": 19,
      "type": "row",
      "title": "Logs",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 22, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 6,
      "gridPos": { "x": 0, "y": 23, "w": 24, "h": 10 },
      "type": "logs",
      "title": "[Component] Log stream",
      "libraryPanel": { "uid": "collops-cmp-log-stream", "name": "[Component] Log stream" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
    }
  ]
}
