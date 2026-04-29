{
  "uid": "[=folder_uid]-inv",
  "title": "Investigation",
  "description": "Tier 3 — correlate logs, metrics and traces for any CollOps service. Exemplar dots on the metrics panel link to traces in Tempo. TraceId values in the log panel also link to Tempo.",
  "tags": ["collops", "investigation", "tier3"],
  "timezone": "browser",
  "refresh": "30s",
  "schemaVersion": 39,
  "time": { "from": "now-1h", "to": "now" },
  "timepicker": {},
  "links": [
    {
      "title": "CollOps Overview",
      "url": "/d/[=folder_uid]-ov",
      "type": "link",
      "icon": "arrow-left",
      "tooltip": "Back to fleet overview",
      "targetBlank": false,
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
      }
    ]
  },
  "panels": [
    {
      "id": 10,
      "type": "row",
      "title": "Metrics",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 0, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 1,
      "gridPos": { "x": 0, "y": 1, "w": 24, "h": 9 },
      "type": "timeseries",
      "title": "RED metrics — $service_name",
      "description": "Exemplar dots (hollow circles) link to individual traces in Tempo. Click one to jump to the trace.",
      "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "expr": "sum(rate(http_server_request_duration_seconds_count{[=app_selector],service_name=\"$service_name\",instance=~\"$instance\"}[$__rate_interval]))",
          "legendFormat": "Request rate",
          "refId": "A",
          "exemplar": true
        },
        {
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "expr": "sum(rate(http_server_request_duration_seconds_count{[=app_selector],service_name=\"$service_name\",instance=~\"$instance\",http_response_status_code=~\"5..\"}[$__rate_interval]))",
          "legendFormat": "Error rate",
          "refId": "B",
          "exemplar": true
        },
        {
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "expr": "histogram_quantile(0.99, sum by (le) (rate(http_server_request_duration_seconds_bucket{[=app_selector],service_name=\"$service_name\",instance=~\"$instance\"}[$__rate_interval])))",
          "legendFormat": "p99 latency",
          "refId": "C",
          "exemplar": true
        }
      ],
      "options": {
        "tooltip": { "mode": "multi", "sort": "none" },
        "legend": { "displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull"] }
      },
      "fieldConfig": {
        "defaults": {
          "color": { "mode": "palette-classic" },
          "custom": { "lineWidth": 2, "fillOpacity": 8, "showPoints": "never" }
        },
        "overrides": [
          {
            "matcher": { "id": "byName", "options": "Error rate" },
            "properties": [{ "id": "color", "value": { "mode": "fixed", "fixedColor": "red" } }]
          },
          {
            "matcher": { "id": "byName", "options": "p99 latency" },
            "properties": [{ "id": "unit", "value": "s" }]
          },
          {
            "matcher": { "id": "byRegexp", "options": ".* rate" },
            "properties": [{ "id": "unit", "value": "reqps" }]
          }
        ]
      }
    },
    {
      "id": 11,
      "type": "row",
      "title": "Logs & Traces",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 10, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 2,
      "gridPos": { "x": 0, "y": 11, "w": 14, "h": 14 },
      "type": "logs",
      "title": "Logs — $service_name",
      "description": "Live logs. Click the traceId field in any log line to open the trace in Tempo.",
      "datasource": { "type": "loki", "uid": "[=loki_uid]" },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "[=loki_uid]" },
          "expr": "{[=app_selector],service_name=\"$service_name\",instance=~\"$instance\"}",
          "queryType": "range",
          "refId": "A"
        }
      ],
      "options": {
        "showTime": true,
        "showLabels": false,
        "showCommonLabels": false,
        "wrapLogMessage": true,
        "prettifyLogMessage": true,
        "enableLogDetails": true,
        "dedupStrategy": "none",
        "sortOrder": "Descending"
      },
      "fieldConfig": { "defaults": {}, "overrides": [] }
    },
    {
      "id": 3,
      "gridPos": { "x": 14, "y": 11, "w": 10, "h": 14 },
      "type": "traces",
      "title": "Traces — $service_name",
      "description": "Distributed traces. Click a row to expand the waterfall view.",
      "datasource": { "type": "tempo", "uid": "[=tempo_uid]" },
      "targets": [
        {
          "datasource": { "type": "tempo", "uid": "[=tempo_uid]" },
          "queryType": "traceql",
          "query": "{resource.service.name=\"$service_name\"}",
          "refId": "A",
          "limit": 20,
          "tableType": "traces"
        }
      ],
      "options": { "frameType": "trace" },
      "fieldConfig": { "defaults": {}, "overrides": [] }
    }
  ]
}
