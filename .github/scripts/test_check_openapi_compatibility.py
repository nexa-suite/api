#!/usr/bin/env python3

import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).with_name("check-openapi-compatibility.py")
SPEC = importlib.util.spec_from_file_location("openapi_compatibility", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


def document(operation, schemas=None):
    return {
        "openapi": "3.1.0",
        "paths": {"/items": {"get": operation}},
        "components": {"schemas": schemas or {}},
    }


def operation(**values):
    result = {"responses": {"200": {"description": "OK"}}}
    result.update(values)
    return result


def item_schema(**values):
    result = {"type": "object", "properties": {"id": {"type": "string"}}}
    result.update(values)
    return result


class OpenApiCompatibilityFixtures(unittest.TestCase):
    def assertPass(self, old, new):
        self.assertEqual([], CHECKER.check_compatibility(old, new))

    def assertFail(self, old, new):
        self.assertTrue(CHECKER.check_compatibility(old, new))

    def test_a_compatible_additive_change_passes(self):
        old = document(operation(), {"Item": item_schema()})
        new = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "string"}, "label": {"type": "string"}})})
        self.assertPass(old, new)

    def test_b_removed_path_or_method_fails(self):
        old = document(operation())
        new = {"openapi": "3.1.0", "paths": {}, "components": {"schemas": {}}}
        self.assertFail(old, new)

    def test_c_new_required_parameter_fails(self):
        old = document(operation())
        new = document(operation(parameters=[{
            "name": "X-Tenant", "in": "header", "required": True,
            "schema": {"type": "string"}}]))
        self.assertFail(old, new)

    def test_d_new_required_request_body_fails(self):
        old = document(operation())
        new = document(operation(requestBody={
            "required": True, "content": {"application/json": {"schema": {"type": "object"}}}}))
        self.assertFail(old, new)

    def test_e_referenced_response_property_removed_fails(self):
        old = document(operation(responses={"200": {"content": {
            "application/json": {"schema": {"$ref": "#/components/schemas/Item"}}}}}),
                          {"Item": item_schema(properties={
                              "id": {"type": "string"}, "label": {"type": "string"}})})
        new = document(operation(responses={"200": {"content": {
            "application/json": {"schema": {"$ref": "#/components/schemas/Item"}}}}}),
                          {"Item": item_schema()})
        self.assertFail(old, new)

    def test_f_referenced_property_becomes_required_fails(self):
        old = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "string"}, "label": {"type": "string"}})})
        new = document(operation(), {"Item": item_schema(
            properties={"id": {"type": "string"}, "label": {"type": "string"}},
            required=["label"])})
        self.assertFail(old, new)

    def test_g_enum_value_removed_fails(self):
        old = document(operation(), {"State": {"type": "string", "enum": ["OPEN", "CLOSED"]}})
        new = document(operation(), {"State": {"type": "string", "enum": ["OPEN"]}})
        self.assertFail(old, new)

    def test_h_security_requirement_becomes_incompatible(self):
        old = document(operation(security=[{"bearerAuth": []}]))
        new = document(operation(security=[{"bearerAuth": [], "refreshCookie": []}]))
        self.assertFail(old, new)

    def test_i_referenced_property_type_changes_fails(self):
        old = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "string"}})})
        new = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "integer"}})})
        self.assertFail(old, new)


if __name__ == "__main__":
    unittest.main()
