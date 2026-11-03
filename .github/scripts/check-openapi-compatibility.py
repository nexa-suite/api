#!/usr/bin/env python3
"""Small dependency-free OpenAPI breaking-change gate for committed snapshots."""

import json
import os
import subprocess
import sys

HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}

V2_PRIVATE_BUYER_ACCOUNT_RESPONSE = {
    "version": "2.0.0",
    "path": "/api/v1/client-accounts/me",
    "method": "get",
    "operation_id": "getMyClientAccount",
    "schema": "BuyerClientAccountSelfResponse",
    "historical_schemas": frozenset({"BuyerClientAccountSelfResponse", "ClientAccountDetailResponse"}),
    "responses": ("200", "404"),
    "properties": frozenset({"contactPerson", "contactEmail", "phone", "buyerMembershipId"}),
}


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


def _count_component_references(document, component_name):
    expected_reference = f"#/components/schemas/{component_name}"
    count = 0

    def visit(value):
        nonlocal count
        if isinstance(value, dict):
            if value.get("$ref") == expected_reference:
                count += 1
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(document)
    return count


def _private_buyer_account_response_schema(document, status):
    target = V2_PRIVATE_BUYER_ACCOUNT_RESPONSE
    operation = document.get("paths", {}).get(target["path"], {}).get(target["method"])
    if not isinstance(operation, dict) or operation.get("operationId") != target["operation_id"]:
        return None

    content = operation.get("responses", {}).get(status, {}).get("content", {})
    schema = content.get("*/*", {}).get("schema", {})
    if set(content) != {"*/*"} or not isinstance(schema, dict) or set(schema) != {"$ref"}:
        return None
    return _component_name(schema.get("$ref"))


def _v2_private_buyer_account_exception(base, current):
    """Return the old response schema name when this exact v2.0.0 change applies."""
    target = V2_PRIVATE_BUYER_ACCOUNT_RESPONSE
    if current.get("info", {}).get("version") != target["version"]:
        return None

    old_schema_name = _private_buyer_account_response_schema(base, "200")
    current_schema_name = _private_buyer_account_response_schema(current, "200")
    if (old_schema_name not in target["historical_schemas"]
            or current_schema_name != target["schema"]):
        return None

    # Older baselines reused ClientAccountDetailResponse broadly; that old
    # component is kept under the normal global schema check below. Only the
    # successful self-account representation is covered by this exception.
    old_schema = base.get("components", {}).get("schemas", {}).get(old_schema_name)
    new_schema = current.get("components", {}).get("schemas", {}).get(target["schema"])
    if not isinstance(old_schema, dict) or not isinstance(new_schema, dict):
        return None
    old_properties = old_schema.get("properties", {})
    new_properties = new_schema.get("properties", {})
    if (not target["properties"].issubset(old_properties)
            or target["properties"].intersection(new_properties)):
        return None
    return old_schema_name


def _buyer_account_response_schema_reference_count(document, schema_name):
    target = V2_PRIVATE_BUYER_ACCOUNT_RESPONSE
    return sum(
        _private_buyer_account_response_schema(document, status) == schema_name
        for status in target["responses"]
    )


def _component_private_removals(base, current, schema_name, exception_schema_name):
    target = V2_PRIVATE_BUYER_ACCOUNT_RESPONSE
    base_target_references = _buyer_account_response_schema_reference_count(base, schema_name)
    current_target_references = _buyer_account_response_schema_reference_count(current, schema_name)
    if (exception_schema_name == schema_name == target["schema"]
            and base_target_references > 0
            and _count_component_references(base, schema_name) == base_target_references
            and _count_component_references(current, schema_name) == current_target_references):
        return target["properties"]
    return frozenset()


def _resolve(schema, components):
    seen = set()
    while isinstance(schema, dict):
        name = _component_name(schema.get("$ref"))
        if name is None or name in seen:
            break
        seen.add(name)
        schema = components.get(name, schema)
    return schema


def _nullable_union_value_schema(schema):
    """Normalize supported nullable schemas to their single non-null value branch."""
    if not isinstance(schema, dict):
        return None
    declared_type = schema.get("type")
    if (isinstance(declared_type, list) and len(declared_type) == 2
            and declared_type.count("null") == 1):
        value_type = next(value for value in declared_type if value != "null")
        if isinstance(value_type, str):
            return {**schema, "type": value_type}
    alternatives = schema.get("anyOf")
    if not isinstance(alternatives, list) or len(alternatives) != 2:
        return None
    harmless_metadata = {
        "anyOf", "description", "title", "example", "deprecated", "readOnly", "writeOnly"
    }
    if not set(schema).issubset(harmless_metadata):
        return None

    def is_unconstrained_null_branch(branch):
        return (isinstance(branch, dict)
                and branch.get("type") in ("null", ["null"])
                and set(branch).issubset({"type", "description", "title", "example",
                                          "deprecated", "readOnly", "writeOnly"}))

    null_branches = [branch for branch in alternatives if is_unconstrained_null_branch(branch)]
    value_branches = [branch for branch in alternatives if branch not in null_branches]
    if len(null_branches) == 1 and len(value_branches) == 1:
        return value_branches[0]
    return None


def schema_breaks(old, new, location, old_components, new_components, visited=None,
                  allowed_removed_properties=None):
    if not isinstance(old, dict) or not isinstance(new, dict):
        return []
    old_nullable_value_schema = _nullable_union_value_schema(old)
    nullable_value_schema = _nullable_union_value_schema(new)
    old_has_any_of = "anyOf" in old
    new_has_any_of = "anyOf" in new

    if old_has_any_of and old_nullable_value_schema is None:
        return [] if old == new else [f"{location}: unsupported anyOf schema changed"]
    if new_has_any_of and nullable_value_schema is None:
        return [] if old == new else [f"{location}: unsupported anyOf schema introduced or changed"]

    if old_nullable_value_schema is not None and nullable_value_schema is not None:
        # Compare both value branches. This also keeps later nullable-to-nullable
        # changes subject to the same field, type, enum, and required checks.
        return schema_breaks(
            old_nullable_value_schema, nullable_value_schema, location,
            old_components, new_components, visited, allowed_removed_properties)
    if nullable_value_schema is not None:
        # A null alternative is compatible only when the prior non-null schema
        # remains intact; compare that branch with the existing recursive gate.
        return schema_breaks(
            old, nullable_value_schema, location,
            old_components, new_components, visited, allowed_removed_properties)
    if old_nullable_value_schema is not None:
        return [f"{location}: nullable alternative removed"]

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
            if allowed_removed_properties and name in allowed_removed_properties:
                continue
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
    if new_security and any(not requirement for requirement in new_security):
        if not old_security or any(not requirement for requirement in old_security):
            return []
        return [f"{location}: anonymous security alternative introduced"]
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


def _content_breaks(old_content, new_content, location, old_components, new_components,
                    allowed_removed_properties=None):
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
            old_components, new_components,
            allowed_removed_properties=allowed_removed_properties))
    return failures


def check_compatibility(base, current):
    private_buyer_account_schema = _v2_private_buyer_account_exception(base, current)
    failures = []
    old_components = base.get("components", {}).get("schemas", {})
    new_components = current.get("components", {}).get("schemas", {})

    for name, old_schema in old_components.items():
        if name not in new_components:
            failures.append(f"component schema removed: {name}")
            continue
        failures.extend(schema_breaks(
            old_schema, new_components[name], f"component {name}",
            old_components, new_components,
            allowed_removed_properties=_component_private_removals(
                base, current, name, private_buyer_account_schema)))

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
                allowed_private_fields = frozenset()
                target = V2_PRIVATE_BUYER_ACCOUNT_RESPONSE
                old_response_schema = _private_buyer_account_response_schema(base, status)
                new_response_schema = _private_buyer_account_response_schema(current, status)
                if (private_buyer_account_schema is not None
                        and path == target["path"] and method == target["method"]
                        and status in target["responses"]
                        and old_response_schema in target["historical_schemas"]
                        and new_response_schema == target["schema"]):
                    allowed_private_fields = target["properties"]
                failures.extend(_content_breaks(
                    old_response.get("content", {}),
                    new_op["responses"][status].get("content", {}),
                    f"{location} response {status}", old_components, new_components,
                    allowed_removed_properties=allowed_private_fields))
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
