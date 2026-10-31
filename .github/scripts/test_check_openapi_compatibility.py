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


BUYER_SELF_SCHEMA = "BuyerClientAccountSelfResponse"
BUYER_SELF_PATH = "/api/v1/client-accounts/me"
PRIVATE_BUYER_FIELDS = {"contactPerson", "contactEmail", "phone", "buyerMembershipId"}


def buyer_self_document(version, properties, *, path=BUYER_SELF_PATH,
                        schema_name=BUYER_SELF_SCHEMA, operation_id="getMyClientAccount",
                        not_found_schema=None):
    success_response = {
        "description": "Buyer client account returned",
        "content": {"*/*": {"schema": {"$ref": f"#/components/schemas/{schema_name}"}}},
    }
    not_found_schema = schema_name if not_found_schema is None else not_found_schema
    if not_found_schema == "NexaProblemDetail":
        not_found_response = {
            "description": "Buyer client account not found",
            "content": {"application/problem+json": {"schema": {
                "$ref": "#/components/schemas/NexaProblemDetail",
            }}},
        }
    else:
        not_found_response = {
            "description": "Buyer client account not found",
            "content": {"*/*": {"schema": {
                "$ref": f"#/components/schemas/{not_found_schema}",
            }}},
        }
    schemas = {schema_name: {"type": "object", "properties": properties}}
    if not_found_schema == "NexaProblemDetail":
        schemas["NexaProblemDetail"] = {"type": "object", "properties": {
            "status": {"type": "integer"}, "detail": {"type": "string"},
        }}
    return {
        "openapi": "3.1.0",
        "info": {"version": version},
        "paths": {path: {"get": {
            "operationId": operation_id,
            "responses": {"200": success_response, "404": not_found_response},
        }}},
        "components": {"schemas": schemas},
    }


def buyer_self_base_properties():
    return {name: {"type": "string"} for name in {
        "id", "code", "businessName", "commercialName", "countryCode", "taxType",
        "taxValue", "segment", "deliveryProfile", "paymentCondition", "status",
        "version", *PRIVATE_BUYER_FIELDS,
    }}


def buyer_self_candidate_properties():
    properties = buyer_self_base_properties()
    for name in PRIVATE_BUYER_FIELDS:
        del properties[name]
    properties["accountType"] = {"type": "string"}
    return properties


def add_get_response(document, path, schema_name):
    document["paths"][path] = {"get": {"responses": {
        "200": {"content": {"*/*": {"schema": {
            "$ref": f"#/components/schemas/{schema_name}",
        }}}},
    }}}


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

    def test_h1_optional_auth_alternative_remains_compatible(self):
        old = document(operation())
        new = document(operation(security=[{"bearerAuth": []}, {}]))
        self.assertPass(old, new)

    def test_h2_anonymous_alternative_does_not_remove_existing_authentication(self):
        old = document(operation(security=[{"bearerAuth": []}]))
        new = document(operation(security=[{"bearerAuth": []}, {}]))
        self.assertFail(old, new)

    def test_h3_anonymous_alternative_does_not_remove_inherited_global_authentication(self):
        old = document(operation())
        old["security"] = [{"bearerAuth": []}]
        new = document(operation(security=[{"bearerAuth": []}, {}]))
        new["security"] = [{"bearerAuth": []}]
        self.assertFail(old, new)

    def test_i_referenced_property_type_changes_fails(self):
        old = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "string"}})})
        new = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "integer"}})})
        self.assertFail(old, new)

    def test_j_nullable_any_of_preserves_the_existing_reference(self):
        money = {"type": "object", "properties": {
            "amount": {"type": "string"}, "currency": {"type": "string"}}}
        old = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"$ref": "#/components/schemas/MoneyResponse"}}),
        })
        new = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"anyOf": [
                    {"$ref": "#/components/schemas/MoneyResponse"},
                    {"type": "null"},
                ]}}),
        })
        self.assertPass(old, new)

    def test_k_nullable_any_of_still_fails_when_the_value_schema_breaks(self):
        old = document(operation(), {
            "MoneyResponse": {"type": "object", "properties": {
                "amount": {"type": "string"}, "currency": {"type": "string"}}},
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"$ref": "#/components/schemas/MoneyResponse"}}),
        })
        new = document(operation(), {
            "MoneyResponse": {"type": "object", "properties": {
                "amount": {"type": "string"}}},
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"anyOf": [
                    {"$ref": "#/components/schemas/MoneyResponse"},
                    {"type": "null"},
                ]}}),
        })
        self.assertFail(old, new)

    def test_l_nullable_any_of_does_not_accept_an_unchecked_extra_value_branch(self):
        money = {"type": "object", "properties": {
            "amount": {"type": "string"}, "currency": {"type": "string"}}}
        old = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"$ref": "#/components/schemas/MoneyResponse"}}),
        })
        new = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"anyOf": [
                    {"$ref": "#/components/schemas/MoneyResponse"},
                    {"type": "null"},
                    {"type": "string"},
                ]}}),
        })
        self.assertFail(old, new)

    def test_p_nullable_type_array_preserves_the_existing_value_type(self):
        old = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "string", "format": "uuid"}})})
        new = document(operation(), {"Item": item_schema(properties={
            "id": {"type": ["string", "null"], "format": "uuid"}})})
        self.assertPass(old, new)

    def test_q_nullable_type_array_still_fails_when_value_type_changes(self):
        old = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "string"}})})
        new = document(operation(), {"Item": item_schema(properties={
            "id": {"type": ["integer", "null"]}})})
        self.assertFail(old, new)

    def test_r_nullable_type_removal_fails(self):
        old = document(operation(), {"Item": item_schema(properties={
            "id": {"type": ["string", "null"]}})})
        new = document(operation(), {"Item": item_schema(properties={
            "id": {"type": "string"}})})
        self.assertFail(old, new)

    def test_m_nullable_to_nullable_change_still_checks_value_fields(self):
        old = document(operation(), {
            "MoneyResponse": {"type": "object", "properties": {
                "amount": {"type": "string"}, "currency": {"type": "string"}}},
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"anyOf": [
                    {"$ref": "#/components/schemas/MoneyResponse"},
                    {"type": "null"},
                ]}}),
        })
        new = document(operation(), {
            "MoneyResponse": {"type": "object", "properties": {
                "amount": {"type": "string"}}},
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"anyOf": [
                    {"$ref": "#/components/schemas/MoneyResponse"},
                    {"type": "null"},
                ]}}),
        })
        self.assertFail(old, new)

    def test_n_nullable_any_of_with_validation_sibling_is_rejected(self):
        money = {"type": "object", "properties": {
            "amount": {"type": "string"}, "currency": {"type": "string"}}}
        old = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"$ref": "#/components/schemas/MoneyResponse"}}),
        })
        new = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"anyOf": [
                    {"$ref": "#/components/schemas/MoneyResponse"},
                    {"type": "null"},
                ], "type": "string"}}),
        })
        self.assertFail(old, new)

    def test_o_nullable_any_of_with_constrained_null_branch_is_rejected(self):
        money = {"type": "object", "properties": {
            "amount": {"type": "string"}, "currency": {"type": "string"}}}
        old = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"$ref": "#/components/schemas/MoneyResponse"}}),
        })
        new = document(operation(), {
            "MoneyResponse": money,
            "Item": item_schema(properties={
                "id": {"type": "string"},
                "price": {"anyOf": [
                    {"$ref": "#/components/schemas/MoneyResponse"},
                    {"type": "null", "$ref": "#/components/schemas/MoneyResponse"},
                ]}}),
        })
        self.assertFail(old, new)

    def test_v2_buyer_self_response_allows_only_the_four_private_field_removals(self):
        old = buyer_self_document("1.2.0", buyer_self_base_properties(),
                                  not_found_schema="NexaProblemDetail")
        new = buyer_self_document("2.0.0", buyer_self_candidate_properties(),
                                  not_found_schema="NexaProblemDetail")

        self.assertPass(old, new)

    def test_v2_buyer_self_response_allows_historical_shared_schema_at_this_route_only(self):
        old = buyer_self_document(
            "0.21.0", buyer_self_base_properties(), schema_name="ClientAccountDetailResponse")
        new = buyer_self_document("2.0.0", buyer_self_candidate_properties())
        for document in (old, new):
            document["components"]["schemas"]["ClientAccountDetailResponse"] = item_schema(
                properties=buyer_self_base_properties())
            add_get_response(document, "/api/v1/another-operation", "ClientAccountDetailResponse")

        self.assertPass(old, new)

    def test_v2_buyer_self_exception_still_blocks_other_property_removals(self):
        old = buyer_self_document("1.2.0", buyer_self_base_properties(),
                                  not_found_schema="NexaProblemDetail")
        new_properties = buyer_self_candidate_properties()
        del new_properties["status"]
        new = buyer_self_document("2.0.0", new_properties,
                                  not_found_schema="NexaProblemDetail")

        self.assertFail(old, new)

    def test_v2_buyer_self_exception_is_limited_to_the_named_route(self):
        old = buyer_self_document(
            "1.2.0", buyer_self_base_properties(), path="/api/v1/client-accounts/{id}",
            not_found_schema="NexaProblemDetail")
        new = buyer_self_document(
            "2.0.0", buyer_self_candidate_properties(), path="/api/v1/client-accounts/{id}",
            not_found_schema="NexaProblemDetail")

        self.assertFail(old, new)

    def test_v2_buyer_self_exception_is_limited_to_the_named_schema(self):
        old = buyer_self_document(
            "1.2.0", buyer_self_base_properties(), schema_name="ClientAccountDetailResponse",
            not_found_schema="NexaProblemDetail")
        new = buyer_self_document(
            "2.0.0", buyer_self_candidate_properties(), schema_name="ClientAccountDetailResponse",
            not_found_schema="NexaProblemDetail")

        self.assertFail(old, new)

    def test_v2_buyer_self_exception_is_limited_to_the_named_operation(self):
        old = buyer_self_document("1.2.0", buyer_self_base_properties(),
                                  not_found_schema="NexaProblemDetail")
        new = buyer_self_document(
            "2.0.0", buyer_self_candidate_properties(), operation_id="anotherOperation",
            not_found_schema="NexaProblemDetail")

        self.assertFail(old, new)

    def test_v2_buyer_self_exception_is_limited_to_the_v2_candidate(self):
        old = buyer_self_document("1.2.0", buyer_self_base_properties(),
                                  not_found_schema="NexaProblemDetail")
        new = buyer_self_document("2.0.1", buyer_self_candidate_properties(),
                                  not_found_schema="NexaProblemDetail")

        self.assertFail(old, new)

    def test_v2_buyer_self_exception_is_disabled_when_schema_is_shared(self):
        old = buyer_self_document("1.2.0", buyer_self_base_properties(),
                                  not_found_schema="NexaProblemDetail")
        new = buyer_self_document("2.0.0", buyer_self_candidate_properties(),
                                  not_found_schema="NexaProblemDetail")
        for document in (old, new):
            document["paths"]["/api/v1/another-operation"] = {"get": {
                "responses": {"200": {"content": {"*/*": {"schema": {
                    "$ref": f"#/components/schemas/{BUYER_SELF_SCHEMA}",
                }}}}}
            }}

        self.assertFail(old, new)

    def test_v2_buyer_self_exception_does_not_cover_another_historical_schema_consumer(self):
        old = buyer_self_document(
            "0.21.0", buyer_self_base_properties(), schema_name="ClientAccountDetailResponse",
            not_found_schema="NexaProblemDetail")
        new = buyer_self_document("2.0.0", buyer_self_candidate_properties(),
                                  not_found_schema="NexaProblemDetail")
        for document in (old, new):
            document["components"]["schemas"]["ClientAccountDetailResponse"] = item_schema(
                properties=buyer_self_base_properties())
        add_get_response(old, "/api/v1/another-operation", "ClientAccountDetailResponse")
        add_get_response(new, "/api/v1/another-operation", BUYER_SELF_SCHEMA)

        failures = CHECKER.check_compatibility(old, new)
        self.assertIn(
            "GET /api/v1/another-operation response 200 */*: property removed: contactPerson",
            failures)

    def test_v2_buyer_self_exception_does_not_hide_global_historical_schema_changes(self):
        old = buyer_self_document(
            "0.21.0", buyer_self_base_properties(), schema_name="ClientAccountDetailResponse",
            not_found_schema="NexaProblemDetail")
        new = buyer_self_document("2.0.0", buyer_self_candidate_properties(),
                                  not_found_schema="NexaProblemDetail")
        old["components"]["schemas"]["ClientAccountDetailResponse"] = item_schema(
            properties=buyer_self_base_properties())
        new["components"]["schemas"]["ClientAccountDetailResponse"] = item_schema(
            properties=buyer_self_candidate_properties())
        add_get_response(old, "/api/v1/another-operation", "ClientAccountDetailResponse")
        add_get_response(new, "/api/v1/another-operation", "ClientAccountDetailResponse")

        failures = CHECKER.check_compatibility(old, new)
        self.assertIn("component ClientAccountDetailResponse: property removed: contactPerson", failures)

    def test_v2_buyer_self_exception_does_not_hide_changes_to_retained_properties(self):
        old = buyer_self_document("1.2.0", buyer_self_base_properties(),
                                  not_found_schema="NexaProblemDetail")
        new_properties = buyer_self_candidate_properties()
        new_properties["status"] = {"type": "integer"}
        new = buyer_self_document("2.0.0", new_properties,
                                  not_found_schema="NexaProblemDetail")

        self.assertFail(old, new)

    def test_v2_buyer_self_exception_allows_private_fields_in_404_when_schema_stays_account(self):
        old = buyer_self_document(
            "0.21.0", buyer_self_base_properties(), schema_name="ClientAccountDetailResponse")
        new = buyer_self_document("2.0.0", buyer_self_candidate_properties())
        new["components"]["schemas"]["ClientAccountDetailResponse"] = item_schema(
            properties=buyer_self_base_properties())

        self.assertPass(old, new)

    def test_v2_buyer_self_exception_does_not_waive_a_404_problem_detail_transition(self):
        old = buyer_self_document(
            "0.21.0", buyer_self_base_properties(), schema_name="ClientAccountDetailResponse")
        new = buyer_self_document("2.0.0", buyer_self_candidate_properties(),
                                  not_found_schema="NexaProblemDetail")

        failures = CHECKER.check_compatibility(old, new)
        self.assertIn(
            "GET /api/v1/client-accounts/me response 404: media type removed: */*",
            failures)


if __name__ == "__main__":
    unittest.main()
