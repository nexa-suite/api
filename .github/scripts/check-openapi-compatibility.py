#!/usr/bin/env python3
"""Small dependency-free OpenAPI breaking-change gate for committed snapshots."""

import json
import os
import subprocess
import sys

HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}


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
    return {name for name, value in path.items()
            if name.lower() in HTTP_METHODS and isinstance(value, dict)}


def _component_name(reference):
    prefix = "#/components/schemas/"
    return reference[len(prefix):] if isinstance(reference, str) and reference.startswith(prefix) else None


def _resolve(schema, components):
    seen = set()
    while isinstance(schema, dict):
        name = _component_name(schema.get("$ref"))
        if name is None or name in seen:
            break
        seen.add(name)
        schema = components.get(name, schema)
    return schema


def schema_breaks(old, new, location, old_components, new_components, visited=None):
    if not isinstance(old, dict) or not isinstance(new, dict):
        return []
    visited = set() if visited is None else visited
    old_resolved = _resolve(old, old_components)
    new_resolved = _resolve(new, new_components)
    pair = (id(old_resolved), id(new_resolved))
    if pair in visited:
        return []
    visited.add(pair)

    failures = []
    old_type = old_resolved.get("type")
    new_type = new_resolved.get("type")
    if old_type and new_type and old_type != new_type:
        failures.append(f"{location}: schema type changed")

    for enum_value in old_resolved.get("enum", []):
        if enum_value not in new_resolved.get("enum", []):
            failures.append(f"{location}: enum value removed: {enum_value}")

    old_properties = old_resolved.get("properties", {})
    new_properties = new_resolved.get("properties", {})
    for name, old_property in old_properties.items():
        if name not in new_properties:
            failures.append(f"{location}: property removed: {name}")
            continue
        failures.extend(schema_breaks(
            old_property, new_properties[name], f"{location}.{name}",
            old_components, new_components, visited))

    old_required = set(old_resolved.get("required", []))
    new_required = set(new_resolved.get("required", []))
    for name in new_required - old_required:
        failures.append(f"{location}: property became required: {name}")

    if "items" in old_resolved and "items" in new_resolved:
        failures.extend(schema_breaks(
            old_resolved["items"], new_resolved["items"], f"{location}[]",
            old_components, new_components, visited))
    return failures


def _parameters(path_item, operation):
    values = {}
    for parameter in path_item.get("parameters", []) + operation.get("parameters", []):
        values[(parameter.get("in"), parameter.get("name"))] = parameter
    return values


def _security(document, operation):
    return operation["security"] if "security" in operation else document.get("security")


def _requirement_is_no_more_restrictive(new_requirement, old_requirement):
    for scheme, scopes in new_requirement.items():
        if scheme not in old_requirement:
            return False
        if not set(scopes).issubset(set(old_requirement[scheme])):
            return False
    return True


def _security_breaks(old_security, new_security, location):
    if old_security == new_security:
        return []
    if old_security is None:
        return [f"{location}: security requirement introduced"] if new_security else []
    if new_security is None:
        return [f"{location}: security requirement removed"]
    if not old_security:
        return [f"{location}: security requirement changed"]
    failures = []
    for old_requirement in old_security:
        if not any(_requirement_is_no_more_restrictive(new_requirement, old_requirement)
                   for new_requirement in new_security):
            failures.append(f"{location}: security requirement became incompatible")
    return failures


def _content_breaks(old_content, new_content, location, old_components, new_components):
    failures = []
    for media_type, old_media in old_content.items():
        if media_type not in new_content:
            failures.append(f"{location}: media type removed: {media_type}")
            continue
        old_schema = old_media.get("schema", {})
        new_schema = new_content[media_type].get("schema", {})
        if old_schema and not new_schema:
            failures.append(f"{location} {media_type}: schema removed")
            continue
        failures.extend(schema_breaks(
            old_schema, new_schema, f"{location} {media_type}",
            old_components, new_components))
    return failures


def check_compatibility(base, current):
    failures = []
    old_components = base.get("components", {}).get("schemas", {})
    new_components = current.get("components", {}).get("schemas", {})

    for name, old_schema in old_components.items():
        if name not in new_components:
            failures.append(f"component schema removed: {name}")
            continue
        failures.extend(schema_breaks(
            old_schema, new_components[name], f"component {name}",
            old_components, new_components))

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
            location = f"{method.upper()} {path}"

            old_parameters = _parameters(old_path, old_op)
            new_parameters = _parameters(new_paths[path], new_op)
            for key, old_parameter in old_parameters.items():
                if key not in new_parameters:
                    failures.append(f"{location}: parameter removed: {key}")
                    continue
                new_parameter = new_parameters[key]
                if not old_parameter.get("required", False) and new_parameter.get("required", False):
                    failures.append(f"{location}: parameter became required: {key}")
                failures.extend(schema_breaks(
                    old_parameter.get("schema", {}), new_parameter.get("schema", {}),
                    f"{location} parameter {key}", old_components, new_components))
            for key, new_parameter in new_parameters.items():
                if key not in old_parameters and new_parameter.get("required", False):
                    failures.append(f"{location}: required parameter introduced: {key}")

            old_body = old_op.get("requestBody")
            new_body = new_op.get("requestBody")
            if old_body and not new_body:
                failures.append(f"{location}: request body removed")
            elif new_body:
                if (not old_body or not old_body.get("required", False)) and new_body.get("required", False):
                    failures.append(f"{location}: required request body introduced")
                if old_body:
                    failures.extend(_content_breaks(
                        old_body.get("content", {}), new_body.get("content", {}),
                        f"{location} request body", old_components, new_components))

            failures.extend(_security_breaks(
                _security(base, old_op), _security(current, new_op), location))

            for status, old_response in old_op.get("responses", {}).items():
                if status not in new_op.get("responses", {}):
                    failures.append(f"{location}: response removed: {status}")
                    continue
                failures.extend(_content_breaks(
                    old_response.get("content", {}),
                    new_op["responses"][status].get("content", {}),
                    f"{location} response {status}", old_components, new_components))
    return failures


def main():
    base_ref = os.environ.get("OPENAPI_BASE_REF", "origin/develop")
    base = load_base(base_ref)
    if base is None:
        return 2
    with open("docs/openapi/openapi.json", encoding="utf-8") as handle:
        current = json.load(handle)
    failures = check_compatibility(base, current)
    if failures:
        print("OpenAPI breaking changes detected:")
        print("\n".join(f"- {failure}" for failure in failures))
        return 1
    print(f"OpenAPI compatibility PASS against {base_ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
