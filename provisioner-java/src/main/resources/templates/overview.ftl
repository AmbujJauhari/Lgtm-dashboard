{
  "uid": "[=folder_uid]-overview",
  "title": "CollOps Overview",
  "description": "Tier 1 — aggregate view. Click any service to drill into its component dashboard.",
  "tags": ["collops", "aggregate", "tier1"],
  "timezone": "browser",
  "refresh": "30s",
  "schemaVersion": 39,
  "time": { "from": "now-1h", "to": "now" },
  "timepicker": {},
  "templating": { "list": [] },
  "links": [],
  "panels": [
    {
      "id": 10,
      "type": "row",
      "title": "Health & Error Rates",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 0, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 1,
      "gridPos": { "x": 0, "y": 1, "w": 24, "h": 10 },
      "type": "state-timeline",
      "title": "Service health (error rate %)",
      "description": "HTTP 5xx error rate per service over time. Green <1%, yellow 1-5%, red >5%. Click a row to open the service dashboard.",
      "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "expr": "100 * sum by (service_name) (rate(http_server_request_duration_seconds_count{[=app_selector],http_response_status_code=~\"5..\"}[$__rate_interval])) / sum by (service_name) (rate(http_server_request_duration_seconds_count{[=app_selector]}[$__rate_interval]))",
          "legendFormat": "{{service_name}}",
          "refId": "A"
        }
      ],
      "options": { "fillOpacity": 80, "showValue": "auto", "alignValue": "center", "rowHeight": 0.9,
                   "legend": { "displayMode": "list", "placement": "bottom" },
                   "tooltip": { "mode": "single", "sort": "none" } },
      "fieldConfig": {
        "defaults": {
          "unit": "percent",
          "min": 0,
          "thresholds": { "mode": "absolute", "steps": [
            { "color": "green", "value": null },
            { "color": "yellow", "value": 1 },
            { "color": "red", "value": 5 }
          ]},
          "custom": { "fillOpacity": 80, "lineWidth": 0, "spanNulls": false },
          "links": [
            {
              "title": "Open ${__series.name} dashboard",
              "url": "/d/[=folder_uid]-service-overview?var-service_name=${__series.name}&${__url_time_range}",
              "targetBlank": false
            }
          ]
        },
        "overrides": []
      }
    },
    {
      "id": 4,
      "gridPos": { "x": 0, "y": 11, "w": 24, "h": 8 },
      "type": "table",
      "title": "Error rate per service",
      "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "expr": "100 * sum by (service_name) (rate(http_server_request_duration_seconds_count{[=app_selector],http_response_status_code=~\"5..\"}[$__rate_interval])) / sum by (service_name) (rate(http_server_request_duration_seconds_count{[=app_selector]}[$__rate_interval]))",
          "legendFormat": "{{service_name}}",
          "refId": "A",
          "instant": true,
          "format": "table"
        }
      ],
      "options": { "footer": { "show": false } },
      "fieldConfig": {
        "defaults": {
          "unit": "percent",
          "thresholds": { "mode": "absolute", "steps": [
            { "color": "green", "value": null }, { "color": "yellow", "value": 1 }, { "color": "red", "value": 5 }
          ]},
          "links": [
            {
              "title": "Open service dashboard",
              "url": "/d/[=folder_uid]-service-overview?var-service_name=${__data.fields.service_name}&${__url_time_range}",
              "targetBlank": false
            }
          ]
        },
        "overrides": [
          { "matcher": { "id": "byName", "options": "Value" },
            "properties": [{ "id": "displayName", "value": "Error rate %" }, { "id": "custom.displayMode", "value": "color-background" }] },
          { "matcher": { "id": "byName", "options": "service_name" },
            "properties": [{ "id": "displayName", "value": "Service" }] }
        ]
      }
    },
    {
      "id": 11,
      "type": "row",
      "title": "Performance",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 19, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 2,
      "gridPos": { "x": 0, "y": 20, "w": 12, "h": 10 },
      "type": "barchart",
      "title": "Exception count per component",
      "description": "Total exceptions per service in the selected range. Click a bar to open the service dashboard.",
      "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "expr": "sort_desc(sum by (service_name) (increase(process_exceptions_total{[=app_selector]}[$__range])))",
          "legendFormat": "{{service_name}}",
          "refId": "A",
          "instant": true
        }
      ],
      "options": { "orientation": "horizontal", "barWidth": 0.7, "fillOpacity": 80,
                   "legend": { "displayMode": "hidden" }, "tooltip": { "mode": "single" } },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "color": { "mode": "fixed", "fixedColor": "orange" },
          "links": [
            {
              "title": "Open ${__series.name} dashboard",
              "url": "/d/[=folder_uid]-service-overview?var-service_name=${__series.name}&${__url_time_range}",
              "targetBlank": false
            }
          ]
        },
        "overrides": []
      }
    },
    {
      "id": 3,
      "gridPos": { "x": 12, "y": 20, "w": 12, "h": 10 },
      "type": "table",
      "title": "Top slowest services (p99 latency)",
      "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "[=mimir_uid]" },
          "expr": "sort_desc(histogram_quantile(0.99, sum by (service_name, le) (rate(http_server_request_duration_seconds_bucket{[=app_selector]}[$__rate_interval]))))",
          "legendFormat": "{{service_name}}",
          "refId": "A",
          "instant": true,
          "format": "table"
        }
      ],
      "options": { "footer": { "show": false }, "sortBy": [{ "displayName": "p99 latency", "desc": true }] },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "custom": { "align": "auto", "displayMode": "auto" },
          "thresholds": { "mode": "absolute", "steps": [
            { "color": "green", "value": null }, { "color": "yellow", "value": 0.5 }, { "color": "red", "value": 1 }
          ]},
          "links": [
            {
              "title": "Open service dashboard",
              "url": "/d/[=folder_uid]-service-overview?var-service_name=${__data.fields.service_name}&${__url_time_range}",
              "targetBlank": false
            }
          ]
        },
        "overrides": [
          { "matcher": { "id": "byName", "options": "Value" },
            "properties": [{ "id": "displayName", "value": "p99 latency" }, { "id": "custom.displayMode", "value": "color-background" }] },
          { "matcher": { "id": "byName", "options": "service_name" },
            "properties": [{ "id": "displayName", "value": "Service" }] }
        ]
      }
    },
    {
      "id": 12,
      "type": "row",
      "title": "Topology",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 30, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 5,
      "gridPos": { "x": 0, "y": 31, "w": 24, "h": 14 },
      "type": "nodeGraph",
      "title": "Service dependency graph",
      "description": "Live topology map generated from trace data. Node size = request rate, edge colour = error rate. Requires Tempo metrics generator (enabled by default in grafana/otel-lgtm).",
      "datasource": { "type": "tempo", "uid": "[=tempo_uid]" },
      "targets": [
        {
          "datasource": { "type": "tempo", "uid": "[=tempo_uid]" },
          "queryType": "serviceMap",
          "refId": "A"
        }
      ],
      "options": {},
      "fieldConfig": { "defaults": {}, "overrides": [] }
    }
  ]
}
