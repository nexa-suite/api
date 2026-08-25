#!/usr/bin/env python3
"""Small dependency-free OpenAPI breaking-change gate for committed snapshots."""

import json
import os
import subprocess
import sys


def load_base(ref: str):
    try:
        raw = subprocess.check_output(
            ["git", "show", f"{ref}:docs/openapi/openapi.json"], text=True
        )
    except subprocess.CalledProcessError:
        print(f"OpenAPI base snapshot unavailable: {ref}", file=sys.stderr)
        return None
    return json.loads(raw)


def methods(path):
    return {name for name, value in path.items() if name.lower() in {
        "get", "put", "post", "delete", "options", "head", "patch", "trace"
    } and isinstance(value, dict)}


def schema_breaks(old, new, location, failures):
    if not isinstance(old, dict) or not isinstance(new, dict):
        return
    if old.get("type") and new.get("type") and old["type"] != new["type"]:
        failures.append(f"{location}: schema type changed")
    old_props = old.get("properties", {})
    new_props = new.get("properties", {})
    for name in old_props:
        if name not in new_props:
            failures.append(f"{location}: property removed: {name}")
        else:
            schema_breaks(old_props[name], new_props[name], f"{location}.{name}", failures)
    old_required = set(old.get("required", []))
    new_required = set(new.get("required", []))
    for name in new_required - old_required:
        failures.append(f"{location}: property became required: {name}")
    for name in old.get("enum", []):
        if name not in new.get("enum", []):
            failures.append(f"{location}: enum value removed: {name}")


def main():
    base_ref = os.environ.get("OPENAPI_BASE_REF", "origin/develop")
    base = load_base(base_ref)
    if base is None:
        return 2
    with open("docs/openapi/openapi.json", encoding="utf-8") as handle:
        current = json.load(handle)
    failures = []

    old_paths = base.get("paths", {})
    new_paths = current.get("paths", {})
    for path, old_path in old_paths.items():
        if path not in new_paths:
            failures.append(f"operation path removed: {path}")
            continue
        for method in methods(old_path):
            if method not in methods(new_paths[path]):
                failures.append(f"operation removed: {method.upper()} {path}")
                continue
            old_op = old_path[method]
            new_op = new_paths[path][method]
            old_parameters = {(p.get("in"), p.get("name")): p for p in old_op.get("parameters", [])}
            new_parameters = {(p.get("in"), p.get("name")): p for p in new_op.get("parameters", [])}
            for key, old_parameter in old_parameters.items():
                if key not in new_parameters:
                    failures.append(f"{method.upper()} {path}: parameter removed: {key}")
                    continue
                if old_parameter.get("required", False) is False and new_parameters[key].get("required", False):
                    failures.append(f"{method.upper()} {path}: parameter became required: {key}")
                schema_breaks(old_parameter.get("schema", {}), new_parameters[key].get("schema", {}),
                              f"{method.upper()} {path} parameter {key}", failures)
            old_body = old_op.get("requestBody", {})
            new_body = new_op.get("requestBody", {})
            if old_body and not new_body:
                failures.append(f"{method.upper()} {path}: request body removed")
            if old_body.get("required", False) is False and new_body.get("required", False):
                failures.append(f"{method.upper()} {path}: request body became required")
            for status, old_response in old_op.get("responses", {}).items():
                if status not in new_op.get("responses", {}):
                    failures.append(f"{method.upper()} {path}: response removed: {status}")
                    continue
                old_content = old_response.get("content", {})
                new_content = new_op["responses"][status].get("content", {})
                for media_type, old_media in old_content.items():
                    if media_type not in new_content:
                        failures.append(f"{method.upper()} {path} {status}: media type removed: {media_type}")
                    else:
                        schema_breaks(old_media.get("schema", {}), new_content[media_type].get("schema", {}),
                                      f"{method.upper()} {path} {status} {media_type}", failures)

    if failures:
        print("OpenAPI breaking changes detected:")
        print("\n".join(f"- {failure}" for failure in failures))
        return 1
    print(f"OpenAPI compatibility PASS against {base_ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
