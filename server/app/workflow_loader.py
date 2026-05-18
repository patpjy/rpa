"""Workflow YAML loader — reuses V9.1 schema unchanged.

Looks for *.yaml in the project-level workflows/ dir (../../workflows from this file).
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

WORKFLOWS_DIR = Path(__file__).parent.parent.parent / "workflows"


def list_workflows() -> list[dict[str, Any]]:
    """Mirrors V9.1 dashboard.list_workflows()."""
    items: list[dict[str, Any]] = []
    if not WORKFLOWS_DIR.is_dir():
        return items
    for path in sorted(WORKFLOWS_DIR.glob("*.yaml")):
        if path.name.startswith("_"):
            continue
        wf_id = path.stem
        try:
            with path.open("r", encoding="utf-8") as f:
                data = yaml.safe_load(f) or {}
        except Exception:
            continue
        meta = data.get("meta") or {}
        workflow = data.get("workflow") or {}
        drafts = data.get("drafts") or {}
        items.append(
            {
                "id": wf_id,
                "name": meta.get("name") or wf_id,
                "description": meta.get("description") or "",
                "default_keyword": workflow.get("keyword") or "",
                "default_max_items": int(workflow.get("max_items") or 1),
                "default_dm_template": drafts.get("dm_template") or "",
            }
        )
    return items


def load_workflow(workflow_id: str) -> dict[str, Any]:
    path = WORKFLOWS_DIR / f"{workflow_id}.yaml"
    if not path.exists():
        raise FileNotFoundError(f"workflow not found: {workflow_id}")
    with path.open("r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def apply_overrides(workflow_dict: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
    """Mirror V9.1 STATE.override_* merge logic — applies per-device overrides
    onto a loaded workflow before pushing to the device."""
    out = dict(workflow_dict)
    workflow = dict(out.get("workflow") or {})
    drafts = dict(out.get("drafts") or {})

    if overrides.get("keyword"):
        workflow["keyword"] = str(overrides["keyword"]).strip()
    if overrides.get("max_items"):
        try:
            workflow["max_items"] = max(1, int(overrides["max_items"]))
        except (TypeError, ValueError):
            pass
    if overrides.get("dm_template"):
        drafts["dm_template"] = str(overrides["dm_template"])

    out["workflow"] = workflow
    out["drafts"] = drafts
    return out
