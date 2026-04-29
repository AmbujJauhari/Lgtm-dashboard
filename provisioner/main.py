#!/usr/bin/env python3
"""
CollOps Grafana provisioner.

Usage:
  python main.py deploy --env <env> --folder-uid <uid> [--folder-title <title>]

The folder must already exist in Grafana (create it manually in the UI), OR
pass --folder-title to create it automatically if it does not exist.

Dashboard UIDs are namespaced by folder-uid so multiple deployments can
coexist on the same Grafana instance without conflicts:

  collops-qa   → collops-qa-overview, collops-qa-service-overview, collops-qa-investigation
  collops-uat  → collops-uat-overview, collops-uat-service-overview, collops-uat-investigation

Library panels are org-scoped (no namespace) and shared across all deployments
on the same instance — they are identical regardless of environment.
"""

import argparse
import json
import os
import sys
from pathlib import Path

import yaml
from jinja2 import Environment, FileSystemLoader

sys.path.insert(0, str(Path(__file__).parent))
from grafana_client import GrafanaClient, GrafanaError

BASE_DIR    = Path(__file__).parent.parent
TEMPLATES   = BASE_DIR / "templates"
LIB_PANELS  = BASE_DIR / "library_panels"
CONFIG_DIR  = BASE_DIR / "config"


def load_env_config(env: str) -> dict:
    with open(CONFIG_DIR / "environments.yaml") as f:
        configs = yaml.safe_load(f)
    if env not in configs:
        print(f"ERROR: unknown environment '{env}'. Available: {list(configs)}", file=sys.stderr)
        sys.exit(1)
    return configs[env]


def make_client(cfg: dict) -> GrafanaClient:
    token = os.environ.get(cfg.get("grafana_token_env", "GRAFANA_TOKEN"))
    user  = os.environ.get("GRAFANA_USER", "admin")
    pw    = os.environ.get("GRAFANA_PASSWORD", "admin")
    return GrafanaClient(
        base_url=cfg["grafana_url"],
        token=token or None,
        user=user  if not token else None,
        password=pw if not token else None,
    )


def inject(obj, replacements: dict):
    """Recursively replace placeholder strings in a JSON structure."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if isinstance(v, str):
                for old, new in replacements.items():
                    v = v.replace(old, new)
                obj[k] = v
            else:
                inject(v, replacements)
    elif isinstance(obj, list):
        for item in obj:
            inject(item, replacements)


def cmd_deploy(args):
    cfg        = load_env_config(args.env)
    client     = make_client(cfg)
    ds         = cfg.get("datasources", {})
    folder_uid = args.folder_uid

    print(f"\n{'='*60}")
    print(f"  Deploy  env={args.env}  folder={folder_uid}")
    print(f"  Grafana {cfg['grafana_url']}")
    print(f"{'='*60}\n")

    if not client.health():
        print("ERROR: Grafana not reachable.", file=sys.stderr)
        sys.exit(1)

    # ── 1. Ensure target folder exists ───────────────────────────────────────
    folder = client.get_folder(folder_uid)
    if folder is None:
        if args.folder_title:
            folder = client.create_folder(args.folder_title, folder_uid)
            print(f"  [created]  folder: {args.folder_title} (uid={folder_uid})")
        else:
            print(
                f"  ERROR: folder uid='{folder_uid}' not found in Grafana.\n"
                f"  Create it in the Grafana UI first, or re-run with --folder-title to auto-create.",
                file=sys.stderr,
            )
            sys.exit(1)
    else:
        print(f"  [found]    folder: {folder.get('title')} (uid={folder_uid})")

    # Shared placeholder → value map used in all templates
    replacements = {
        "__MIMIR__":  ds.get("mimir", "prometheus"),
        "__LOKI__":   ds.get("loki",  "loki"),
        "__TEMPO__":  ds.get("tempo", "tempo"),
        "__NS__":     folder_uid,   # namespace prefix for dashboard UIDs + data links
    }

    # ── 2. Library panels (org-scoped, shared across all deployments) ─────────
    print()
    lib_folder_uid = cfg.get("library_panels_folder_uid")
    for category in ("aggregate", "component"):
        for panel_file in sorted((LIB_PANELS / category).glob("*.json")):
            with open(panel_file) as f:
                panel = json.load(f)
            inject(panel.get("model", {}), replacements)
            client.upsert_library_element(panel, folder_uid=lib_folder_uid)

    # ── 3. Dashboards (namespaced by folder-uid) ───────────────────────────────
    print()
    jinja = Environment(loader=FileSystemLoader(str(TEMPLATES)), autoescape=False)

    for template_name in ("overview.json.j2", "service_overview.json.j2", "investigation.json.j2"):
        tmpl      = jinja.get_template(template_name)
        rendered  = tmpl.render(**{k.strip("_"): v for k, v in {
            "folder_uid": folder_uid,
            "mimir_uid":  ds.get("mimir", "prometheus"),
            "loki_uid":   ds.get("loki",  "loki"),
            "tempo_uid":  ds.get("tempo", "tempo"),
        }.items()})
        dashboard = json.loads(rendered)
        client.upsert_dashboard(dashboard, folder_uid)

    print(f"\n{'='*60}")
    print("  Deploy complete.")
    print(f"{'='*60}\n")


def main():
    parser = argparse.ArgumentParser(
        description="CollOps Grafana provisioner — deploy dashboards into a folder",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("deploy", help="Deploy dashboards + library panels into a Grafana folder")
    p.add_argument("--env",          required=True, metavar="ENV",
                   help="Environment name from environments.yaml")
    p.add_argument("--folder-uid",   required=True, metavar="UID",
                   help="UID of the target Grafana folder (create manually in UI first)")
    p.add_argument("--folder-title", default=None, metavar="TITLE",
                   help="If provided, auto-create the folder when it does not exist")

    args = parser.parse_args()

    try:
        cmd_deploy(args)
    except GrafanaError as e:
        print(f"\nGrafana API error: {e}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
