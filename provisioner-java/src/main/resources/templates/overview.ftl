{
  "uid": "[=folder_uid]-ov",
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
      "title": "[Aggregate] Service health",
      "libraryPanel": { "uid": "[=folder_uid]-hh", "name": "[Aggregate] Service health" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
    },
    {
      "id": 4,
      "gridPos": { "x": 0, "y": 11, "w": 24, "h": 8 },
      "type": "table",
      "title": "[Aggregate] Error rate per service",
      "libraryPanel": { "uid": "[=folder_uid]-eb", "name": "[Aggregate] Error rate per service" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
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
      "title": "[Aggregate] Exception count per component",
      "libraryPanel": { "uid": "[=folder_uid]-ec", "name": "[Aggregate] Exception count per component" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
    },
    {
      "id": 3,
      "gridPos": { "x": 12, "y": 20, "w": 12, "h": 10 },
      "type": "table",
      "title": "[Aggregate] Top slowest services (p99 latency)",
      "libraryPanel": { "uid": "[=folder_uid]-ts", "name": "[Aggregate] Top slowest services" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
    },
    {
      "id": 13,
      "type": "row",
      "title": "Logs",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 30, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 6,
      "gridPos": { "x": 0, "y": 31, "w": 24, "h": 10 },
      "type": "barchart",
      "title": "[Aggregate] Log volume by severity",
      "libraryPanel": { "uid": "[=folder_uid]-lv", "name": "[Aggregate] Log volume by severity" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "options": {}
    },
    {
      "id": 12,
      "type": "row",
      "title": "Topology",
      "collapsed": false,
      "gridPos": { "x": 0, "y": 41, "w": 24, "h": 1 },
      "panels": []
    },
    {
      "id": 5,
      "gridPos": { "x": 0, "y": 42, "w": 24, "h": 14 },
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
