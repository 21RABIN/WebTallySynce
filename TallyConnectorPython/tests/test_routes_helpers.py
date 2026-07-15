import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = PROJECT_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))


from tally_connector.routes import (  # noqa: E402
    _attendance_types_export_available,
    _attendance_voucher_feature_unavailable_detail,
    _compact_import_result,
    _company_names_match,
    _connector_capability_matrix,
    _connector_capability_summary,
    _connector_route_inventory,
    _connector_route_inventory_summary,
    _empty_company_list_response,
    _empty_or_ambiguous_report_response,
    _export_price_levels,
    _extract_company_name,
    _export_tdl_payroll_route,
    _validate_common_generate_voucher_type,
    _validate_common_ready_voucher_type,
    _import_result_has_error,
    _is_null_envelope_response,
    _is_tdl_scaffold_placeholder_response,
    company_open,
    company_create,
    employees_list,
    price_structures,
    report_tds_outstandings,
    security_roles,
    security_roles_update,
    _normalize_company_write_payload,
    _normalize_import_result,
    _inventory_material_voucher_write_no_effect_detail,
    _payload_contains_company_name,
    _payroll_collection_unavailable_detail,
    _report_filter_summary,
    _shape_report_vouchers,
    _validate_attendance_type_payload,
    _validate_employee_payload,
    _validate_employee_group_payload,
    _validate_pay_head_payload,
    _tdl_feature_contract,
    _tdl_runtime_status,
    _prepare_order_create_payload,
    _prepare_inventory_note_ledger_fallback_payload,
    _unwrap_single_voucher_payload,
    _validate_company_create_payload,
)
from tally_connector.config import Settings  # noqa: E402
from fastapi import HTTPException  # noqa: E402
from tally_connector.xml_builder import build_tdl_gateway_report  # noqa: E402


class RoutesHelperTests(unittest.TestCase):
    def test_connector_capability_summary_counts_known_sections(self):
        settings = Settings(
            tdl_integration_enabled=False,
            experimental_tally_company_open_enabled=False,
            experimental_tally_company_create_enabled=False,
            experimental_tally_security_roles_enabled=False,
        )
        matrix = _connector_capability_matrix(settings)
        summary = _connector_capability_summary(matrix)
        self.assertGreaterEqual(summary["native_xml_count"], 1)
        self.assertGreaterEqual(summary["tdl_backed_or_required_count"], 8)
        self.assertIn("/employees", matrix["tdl_backed_or_required"])

    def test_connector_route_inventory_includes_expected_statuses(self):
        settings = Settings(
            tdl_integration_enabled=False,
            experimental_tally_company_open_enabled=False,
            experimental_tally_company_create_enabled=False,
            experimental_tally_security_roles_enabled=False,
        )
        inventory = _connector_route_inventory(settings)
        route_map = {entry["path"]: entry for entry in inventory}
        self.assertEqual(route_map["/health"]["status"], "implemented")
        self.assertEqual(route_map["/companies/open"]["status"], "tdl_or_experimental")
        self.assertEqual(route_map["/settings/security-roles"]["status"], "tdl_or_experimental")
        self.assertEqual(route_map["/reports/form-24q"]["status"], "tally_build_dependent")
        self.assertEqual(route_map["/vouchers/einvoice/generate"]["status"], "partial")

    def test_connector_route_inventory_summary_counts_statuses(self):
        settings = Settings(tdl_integration_enabled=False)
        summary = _connector_route_inventory_summary(_connector_route_inventory(settings))
        self.assertGreaterEqual(summary["implemented"], 1)
        self.assertGreaterEqual(summary["partial"], 1)
        self.assertGreaterEqual(summary["tdl_or_experimental"], 1)

    def test_is_null_envelope_response_detects_only_null_envelope_shape(self):
        self.assertTrue(_is_null_envelope_response({"ENVELOPE": None}))
        self.assertFalse(_is_null_envelope_response({"ENVELOPE": {}}))
        self.assertFalse(_is_null_envelope_response({"DATA": []}))

    def test_report_filter_summary_normalizes_known_filters(self):
        self.assertEqual(
            _report_filter_summary(
                stock_item=" Product1 ",
                godown=" Main Location ",
                from_date="2026-04-01",
                to_date="20260430",
            ),
            {
                "stock_item": "Product1",
                "godown": "Main Location",
                "from_date": "20260401",
                "to_date": "20260430",
            },
        )

    def test_empty_or_ambiguous_report_response_returns_clear_payload(self):
        payload = _empty_or_ambiguous_report_response(
            "Item Batch Summary",
            {"stock_item": "Product1"},
        )
        self.assertEqual(payload["source_report"], "Item Batch Summary")
        self.assertEqual(payload["report_state"], "empty_or_unsupported")
        self.assertEqual(payload["filters"], {"stock_item": "Product1"})
        self.assertEqual(payload["items"], [])
        self.assertIn("ENVELOPE=null", payload["reason"])

    def test_shape_report_vouchers_marks_empty_filtered_result(self):
        payload = _shape_report_vouchers({}, "Day Book", voucher_type="Payroll")
        self.assertEqual(payload["source_report"], "Day Book")
        self.assertEqual(payload["source_voucher_type"], "Payroll")
        self.assertEqual(payload["report_state"], "empty")
        self.assertEqual(payload["VOUCHER"], [])

    def test_shape_report_vouchers_marks_records_found_for_matching_vouchers(self):
        payload = _shape_report_vouchers(
            {
                "TALLYMESSAGE": [
                    {"VOUCHER": {"VOUCHERTYPENAME": "Payroll", "VOUCHERNUMBER": "1"}},
                    {"VOUCHER": {"VOUCHERTYPENAME": "Sales", "VOUCHERNUMBER": "2"}},
                ]
            },
            "Day Book",
            voucher_type="Payroll",
            fetch=["VOUCHERNUMBER", "VOUCHERTYPENAME"],
        )
        self.assertEqual(payload["source_report"], "Day Book")
        self.assertEqual(payload["source_voucher_type"], "Payroll")
        self.assertEqual(payload["report_state"], "records_found")
        self.assertEqual(
            payload["VOUCHER"],
            [{"VOUCHERTYPENAME": "Payroll", "VOUCHERNUMBER": "1"}],
        )

    def test_unwrap_single_voucher_payload_accepts_wrapped_voucher_array(self):
        payload = _unwrap_single_voucher_payload(
            {
                "VOUCHER": [
                    {
                        "DATE": "20260402",
                        "VOUCHERTYPENAME": "Journal",
                        "VOUCHERNUMBER": "J-1001",
                    }
                ]
            }
        )
        self.assertEqual(
            payload,
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Journal",
                "VOUCHERNUMBER": "J-1001",
            },
        )

    def test_unwrap_single_voucher_payload_accepts_wrapped_voucher_object(self):
        payload = _unwrap_single_voucher_payload(
            {
                "VOUCHER": {
                    "DATE": "20260402",
                    "VOUCHERTYPENAME": "Receipt",
                    "VOUCHERNUMBER": "R-1001",
                }
            }
        )
        self.assertEqual(
            payload,
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Receipt",
                "VOUCHERNUMBER": "R-1001",
            },
        )

    def test_prepare_order_create_payload_normalizes_sales_order_to_balanced_ledgers(self):
        payload = _prepare_order_create_payload(
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Sales Order",
                "PARTYLEDGERNAME": "ramf",
                "PERSISTEDVIEW": "Invoice Voucher View",
                "ISINVOICE": "Yes",
                "REFERENCE": "SO-1001",
                "ALLINVENTORYENTRIES.LIST": [
                    {
                        "STOCKITEMNAME": "ROCK",
                        "ACTUALQTY": "1 PCS",
                        "BILLEDQTY": "1 PCS",
                        "RATE": "1000.00/PCS",
                        "AMOUNT": "-1000.00",
                    }
                ],
                "ALLLEDGERENTRIES.LIST": [
                    {"LEDGERNAME": "ramf", "AMOUNT": "1000.00", "ISPARTYLEDGER": "Yes"},
                    {"LEDGERNAME": "Sales Account", "AMOUNT": "-1000.00", "ISDEEMEDPOSITIVE": "Yes"},
                ],
            },
            "Sales Order",
            "Create",
        )
        self.assertEqual(
            payload["ALLLEDGERENTRIES.LIST"],
            [
                {"LEDGERNAME": "ramf", "AMOUNT": "1000.00", "ISPARTYLEDGER": "Yes", "ISDEEMEDPOSITIVE": "Yes"},
                {"LEDGERNAME": "Sales Account", "AMOUNT": "-1000.00", "ISDEEMEDPOSITIVE": "Yes"},
            ],
        )
        self.assertNotIn("ALLINVENTORYENTRIES.LIST", payload)
        self.assertEqual(payload["PERSISTEDVIEW"], "Invoice Voucher View")
        self.assertEqual(payload["ISINVOICE"], "No")

    def test_prepare_order_create_payload_normalizes_purchase_order_signs(self):
        payload = _prepare_order_create_payload(
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Purchase Order",
                "PARTYLEDGERNAME": "Raj traders",
                "ALLINVENTORYENTRIES.LIST": [
                    {
                        "STOCKITEMNAME": "ROCK",
                        "ACTUALQTY": "1 PCS",
                        "BILLEDQTY": "1 PCS",
                        "RATE": "1000.00/PCS",
                        "AMOUNT": "1000.00",
                    }
                ],
                "ALLLEDGERENTRIES.LIST": [
                    {"LEDGERNAME": "Purchase", "AMOUNT": "1000.00"},
                    {"LEDGERNAME": "Raj traders", "AMOUNT": "-1000.00", "ISDEEMEDPOSITIVE": "Yes", "ISPARTYLEDGER": "Yes"},
                ],
            },
            "Purchase Order",
            "Create",
        )
        self.assertEqual(
            payload["ALLLEDGERENTRIES.LIST"],
            [
                {"LEDGERNAME": "Raj traders", "AMOUNT": "1000.00", "ISPARTYLEDGER": "Yes", "ISDEEMEDPOSITIVE": "No"},
                {"LEDGERNAME": "Purchase", "AMOUNT": "1000.00", "ISDEEMEDPOSITIVE": "Yes"},
            ],
        )
        self.assertNotIn("ALLINVENTORYENTRIES.LIST", payload)

    def test_prepare_inventory_note_ledger_fallback_payload_for_delivery_note(self):
        payload = _prepare_inventory_note_ledger_fallback_payload(
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Delivery Note",
                "PARTYLEDGERNAME": "ramf",
                "ALLINVENTORYENTRIES.LIST": [
                    {
                        "STOCKITEMNAME": "BARLEL",
                        "ACTUALQTY": "1 PCS",
                        "BILLEDQTY": "1 PCS",
                        "RATE": "3.00/PCS",
                        "AMOUNT": "-3.00",
                    }
                ],
                "ALLLEDGERENTRIES.LIST": [
                    {"LEDGERNAME": "ramf", "AMOUNT": "3.00", "ISPARTYLEDGER": "Yes"},
                    {"LEDGERNAME": "Sales Account", "AMOUNT": "-3.00", "ISDEEMEDPOSITIVE": "Yes"},
                ],
            },
            "Delivery Note",
            "Create",
        )
        self.assertEqual(
            payload["ALLLEDGERENTRIES.LIST"],
            [
                {"LEDGERNAME": "ramf", "ISPARTYLEDGER": "Yes", "AMOUNT": "3.00"},
                {"LEDGERNAME": "Sales Account", "ISDEEMEDPOSITIVE": "Yes", "AMOUNT": "-3.00"},
            ],
        )
        self.assertNotIn("ALLINVENTORYENTRIES.LIST", payload)

    def test_prepare_inventory_note_ledger_fallback_payload_for_receipt_note(self):
        payload = _prepare_inventory_note_ledger_fallback_payload(
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Receipt Note",
                "PARTYLEDGERNAME": "Raj traders",
                "ALLINVENTORYENTRIES.LIST": [
                    {
                        "STOCKITEMNAME": "BARLEL",
                        "ACTUALQTY": "1 PCS",
                        "BILLEDQTY": "1 PCS",
                        "RATE": "3.00/PCS",
                        "AMOUNT": "3.00",
                    }
                ],
                "ALLLEDGERENTRIES.LIST": [
                    {"LEDGERNAME": "Purchase", "AMOUNT": "3.00"},
                    {"LEDGERNAME": "Raj traders", "AMOUNT": "-3.00", "ISDEEMEDPOSITIVE": "Yes", "ISPARTYLEDGER": "Yes"},
                ],
            },
            "Receipt Note",
            "Create",
        )
        self.assertEqual(
            payload["ALLLEDGERENTRIES.LIST"],
            [
                {"LEDGERNAME": "Purchase", "AMOUNT": "3.00"},
                {"LEDGERNAME": "Raj traders", "ISDEEMEDPOSITIVE": "Yes", "ISPARTYLEDGER": "Yes", "AMOUNT": "-3.00"},
            ],
        )
        self.assertNotIn("ALLINVENTORYENTRIES.LIST", payload)

    def test_settings_warn_about_insecure_defaults(self):
        settings = Settings(
            agent_key="local-dev-key",
            auth_client_secret="local-dev-key",
            auth_token_secret="local-dev-key",
            auth_accept_static_key=True,
            tdl_integration_enabled=False,
        )
        warnings = settings.insecure_runtime_warnings()
        self.assertGreaterEqual(len(warnings), 4)
        self.assertTrue(any("TDL integration is disabled" in warning for warning in warnings))

    def test_payroll_collection_unavailable_detail_includes_tdl_guidance(self):
        detail = _payroll_collection_unavailable_detail("employee")
        self.assertEqual(detail["required_tdl_feature"], "payroll_exports")
        self.assertEqual(detail["collection_export"], "Employees")
        self.assertTrue(any("TDL" in step for step in detail["what_to_do"]))

    def test_tdl_runtime_status_reads_repo_manifest(self):
        settings = Settings(
            tdl_integration_enabled=False,
            tdl_profile_name="local_connector_profile",
        )
        status = _tdl_runtime_status(settings)
        self.assertTrue(status["manifest_exists"])
        self.assertIn("company_open", status["feature_statuses"])
        self.assertIn("payroll_exports", status["missing_or_scaffold_features"])

    def test_tdl_feature_contract_includes_route_collection_map(self):
        settings = Settings(tdl_integration_enabled=False)
        contract = _tdl_feature_contract(settings, "payroll_exports")
        self.assertEqual(contract["status"], "scaffold")
        self.assertEqual(contract["route_collections"]["/employees"], "LC_EMPLOYEES")

    def test_build_tdl_gateway_report_embeds_payload_contract(self):
        xml = build_tdl_gateway_report(
            "LC Company Create Report",
            payload={"NAME": "Acme", "ACTION": "CREATE", "ACTIVE": True},
        )
        self.assertIn("<ID>LC Company Create Report</ID>", xml)
        self.assertIn("<LCCALLPAYLOADJSON>", xml)
        self.assertIn("<LCCALL_NAME>Acme</LCCALL_NAME>", xml)
        self.assertIn("<LCCALL_ACTION>CREATE</LCCALL_ACTION>", xml)

    def test_validate_company_create_payload_sets_defaults_and_normalizes_dates(self):
        payload = _validate_company_create_payload(
            {
                "NAME": "Acme Pvt Ltd",
                "STATE": "Tamil Nadu",
                "COUNTRY": "India",
                "BOOKSFROM": "2026-04-01",
            }
        )
        self.assertEqual(payload["NAME"], "Acme Pvt Ltd")
        self.assertEqual(payload["BOOKSFROM"], "20260401")
        self.assertEqual(payload["FINANCIALYEARFROM"], "20260401")
        self.assertEqual(payload["STARTINGFROM"], "20260401")
        self.assertEqual(payload["STATENAME"], "Tamil Nadu")
        self.assertEqual(payload["COUNTRYNAME"], "India")
        self.assertEqual(payload["FORMALNAME"], "INR")

    def test_normalize_company_write_payload_creates_mailing_name_list(self):
        payload = _normalize_company_write_payload({"NAME": "Acme"})
        self.assertEqual(payload["MAILINGNAME.LIST"]["MAILINGNAME"], "Acme")
        self.assertEqual(payload["ADDRESS.LIST"]["ADDRESS"], [])

    def test_normalize_import_result_wraps_response_payload(self):
        payload = _normalize_import_result(
            {
                "RESPONSE": {
                    "CREATED": "1",
                    "ALTERED": "0",
                    "DELETED": "0",
                    "LASTVCHID": "0",
                    "LASTMID": "42",
                    "COMBINED": "0",
                    "IGNORED": "0",
                    "ERRORS": "0",
                    "CANCELLED": "0",
                    "EXCEPTIONS": "0",
                }
            }
        )
        self.assertEqual(payload["IMPORTRESULT"]["CREATED"], "1")
        self.assertEqual(payload["IMPORTRESULT"]["LASTMID"], "42")
        self.assertFalse(_import_result_has_error(payload))

    def test_compact_import_result_marks_line_error(self):
        payload = _compact_import_result("A proper Financial year from date is required!")
        self.assertTrue(_import_result_has_error(payload))
        self.assertEqual(
            payload["IMPORTRESULT"]["LINEERROR"],
            "A proper Financial year from date is required!",
        )

    def test_extract_company_name_prefers_first_non_empty_name(self):
        payload = {"COMPANY": {"NAME": ["Software Development", "Software Development"]}}
        self.assertEqual(_extract_company_name(payload), "Software Development")

    def test_payload_contains_company_name_matches_nested_company_list(self):
        payload = {
            "COLLECTION": {
                "COMPANY": [
                    {"NAME": "Alpha"},
                    {"NAME": "Beta"},
                ]
            }
        }
        self.assertTrue(_payload_contains_company_name(payload, "beta"))
        self.assertFalse(_payload_contains_company_name(payload, "gamma"))

    def test_tdl_scaffold_placeholder_response_detects_placeholder_copy(self):
        payload = {
            "LINE": [
                "This is a scaffold TDL package for the local connector.",
                "Add real collections, reports, or functions for the connector endpoints.",
            ]
        }
        self.assertTrue(_is_tdl_scaffold_placeholder_response(payload))

    def test_company_names_match_ignores_case_and_whitespace(self):
        self.assertTrue(_company_names_match(" Software Development ", "software development"))
        self.assertFalse(_company_names_match("Software Development", "Another Company"))

    def test_empty_company_list_response_is_explicit(self):
        payload = _empty_company_list_response("No company is open.")
        self.assertEqual(payload["source_collection"], "List of Companies")
        self.assertEqual(payload["report_state"], "empty_or_unsupported")
        self.assertEqual(payload["items"], [])
        self.assertEqual(payload["reason"], "No company is open.")


class PayHeadValidationTests(unittest.TestCase):
    def test_validate_pay_head_payload_requires_parent(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_pay_head_payload({"NAME": "LC Test"})
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertIn("PARENT is required", ctx.exception.detail["reason"])

    def test_validate_pay_head_payload_rejects_primary_parent(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_pay_head_payload({"NAME": "LC Test", "PARENT": "Primary"})
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertIn("Primary", ctx.exception.detail["reason"])

    def test_validate_pay_head_payload_normalizes_verified_parent(self):
        payload = _validate_pay_head_payload(
            {"NAME": "LC Test", "PARENT": "Indirect Expenses"}
        )
        self.assertEqual(payload["PARENT"], "Indirect Expenses")
        self.assertEqual(payload["FORPAYROLL"], "Yes")


class EmployeeValidationTests(unittest.TestCase):
    def test_validate_employee_payload_requires_name(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_employee_payload({"PARENT": "Staff"}, "Create")
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertIn("NAME is required", ctx.exception.detail["reason"])

    def test_validate_employee_payload_rejects_primary_parent(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_employee_payload({"NAME": "LC Employee", "PARENT": "Primary"}, "Create")
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertIn("Primary", ctx.exception.detail["reason"])

    def test_validate_employee_payload_normalizes_valid_parent(self):
        payload = _validate_employee_payload(
            {"NAME": "LC Employee", "PARENT": "Staff Group", "EMAIL": "a@example.com"},
            "Create",
        )
        self.assertEqual(payload["NAME"], "LC Employee")
        self.assertEqual(payload["PARENT"], "Staff Group")
        self.assertEqual(payload["EMAILID"], "a@example.com")
        self.assertEqual(payload["USEASEMPLOYEE"], "Yes")
        self.assertEqual(payload["FORPAYROLL"], "Yes")

    def test_validate_employee_payload_skips_parent_check_for_delete(self):
        payload = _validate_employee_payload({"NAME": "LC Employee"}, "Delete")
        self.assertEqual(payload["PARENT"], "Primary")
        self.assertEqual(payload["NAME"], "LC Employee")


class EmployeeGroupValidationTests(unittest.TestCase):
    def test_validate_employee_group_payload_requires_name(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_employee_group_payload({}, "Create")
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertIn("NAME is required", ctx.exception.detail["reason"])

    def test_validate_employee_group_payload_normalizes_name(self):
        payload = _validate_employee_group_payload({"NAME": "LC Group"}, "Create")
        self.assertEqual(payload["NAME"], "LC Group")
        self.assertEqual(payload["FORPAYROLL"], "Yes")
        self.assertEqual(payload["ISEMPLOYEEGROUP"], "Yes")

    def test_validate_employee_group_payload_skips_name_check_for_delete(self):
        payload = _validate_employee_group_payload({}, "Delete")
        self.assertEqual(payload["FORPAYROLL"], "Yes")
        self.assertEqual(payload["ISEMPLOYEEGROUP"], "Yes")


class AttendanceTypeValidationTests(unittest.TestCase):
    def test_validate_attendance_type_payload_requires_name(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_attendance_type_payload({}, "Create")
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertIn("NAME is required", ctx.exception.detail["reason"])

    def test_validate_attendance_type_payload_normalizes_name(self):
        payload = _validate_attendance_type_payload({"NAME": "LC Attendance"}, "Create")
        self.assertEqual(payload["NAME"], "LC Attendance")

    def test_validate_attendance_type_payload_skips_name_check_for_delete(self):
        payload = _validate_attendance_type_payload({}, "Delete")
        self.assertEqual(payload, {})

    def test_attendance_voucher_feature_unavailable_detail_mentions_attendance_types(self):
        detail = _attendance_voucher_feature_unavailable_detail()
        self.assertEqual(detail["resource"], "attendance voucher")
        self.assertEqual(detail["error_type"], "feature_unavailable")
        self.assertIn("Attendance voucher creation depends on attendance types", detail["reason"])


class AttendanceVoucherAvailabilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_attendance_types_export_available_false_for_metadata_only_collection(self):
        with patch("tally_connector.routes._export_master", new=AsyncMock(return_value={"ENVELOPE": {"BODY": {"DATA": {}}}})), \
             patch("tally_connector.routes._is_metadata_only_collection", return_value=True):
            self.assertFalse(await _attendance_types_export_available(AsyncMock(), "Ridsys"))

    async def test_attendance_types_export_available_true_when_entries_exist(self):
        with patch(
            "tally_connector.routes._export_master",
            new=AsyncMock(return_value={"ATTENDANCETYPE": [{"NAME": "LC Attendance"}]}),
        ), patch("tally_connector.routes._is_metadata_only_collection", return_value=False), patch(
            "tally_connector.routes._listify_master_entries",
            return_value=[{"NAME": "LC Attendance"}],
        ):
            self.assertTrue(await _attendance_types_export_available(AsyncMock(), "Ridsys"))


class InventoryMaterialVoucherDetailTests(unittest.TestCase):
    def test_material_voucher_write_no_effect_detail_mentions_stock_journal_fallback(self):
        detail = _inventory_material_voucher_write_no_effect_detail(
            "Material In",
            {"IMPORTRESULT": {"CREATED": "0", "EXCEPTIONS": "1"}},
        )
        self.assertEqual(detail["resource"], "material in voucher")
        self.assertEqual(detail["error_type"], "write_no_effect")
        self.assertIn("Stock Journal", " ".join(detail["what_to_do"]))


class CompanyOpenTests(unittest.IsolatedAsyncioTestCase):
    async def test_company_open_returns_already_open_when_company_matches_hint(self):
        settings = Settings()
        with patch(
            "tally_connector.routes._current_company_name_hint",
            new=AsyncMock(return_value="Software Development"),
        ):
            response = await company_open(
                {"COMPANY": "Software Development"},
                settings=settings,
                client=object(),
                x_agent_key="local-dev-key",
                company=None,
                x_company=None,
            )
        self.assertEqual(response["status"], "ok")
        self.assertEqual(response["mode"], "already-open")
        self.assertEqual(response["company"], "Software Development")
        self.assertFalse(response["switched"])

    async def test_company_open_experimental_requires_post_switch_verification(self):
        settings = Settings(experimental_tally_company_open_enabled=True)
        with patch(
            "tally_connector.routes._current_company_name_hint",
            new=AsyncMock(side_effect=["Old Company", "Target Company"]),
        ), patch(
            "tally_connector.routes._post_xml",
            new=AsyncMock(return_value={"status": "ok"}),
        ):
            response = await company_open(
                {"COMPANY": "Target Company"},
                settings=settings,
                client=object(),
                x_agent_key="local-dev-key",
                company=None,
                x_company=None,
            )

        self.assertEqual(response["status"], "ok")
        self.assertEqual(response["mode"], "experimental-native-xml")
        self.assertTrue(response["switched"])

    async def test_company_open_experimental_fails_when_switch_not_verified(self):
        settings = Settings(experimental_tally_company_open_enabled=True)
        with patch(
            "tally_connector.routes._current_company_name_hint",
            new=AsyncMock(side_effect=["Old Company", "Old Company"]),
        ), patch(
            "tally_connector.routes._post_xml",
            new=AsyncMock(return_value={"status": "ok"}),
        ):
            with self.assertRaises(HTTPException) as ctx:
                await company_open(
                    {"COMPANY": "Target Company"},
                    settings=settings,
                    client=object(),
                    x_agent_key="local-dev-key",
                    company=None,
                    x_company=None,
                )

        self.assertEqual(ctx.exception.status_code, 502)
        self.assertEqual(ctx.exception.detail["error_type"], "verification_failed")


class CompanyCreateTests(unittest.IsolatedAsyncioTestCase):
    async def test_company_create_experimental_rejects_no_effect_import_result(self):
        settings = Settings(experimental_tally_company_create_enabled=True)
        with patch(
            "tally_connector.routes._post_xml_import_result",
            new=AsyncMock(return_value=_compact_import_result("")),
        ):
            with self.assertRaises(HTTPException) as ctx:
                await company_create(
                    {"NAME": "New Company", "STATE": "Tamil Nadu", "COUNTRY": "India"},
                    company=None,
                    x_company=None,
                    x_agent_key="local-dev-key",
                    settings=settings,
                    client=object(),
                )

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["error_type"], "write_no_effect")

    async def test_company_create_experimental_verifies_company_list_on_success(self):
        settings = Settings(experimental_tally_company_create_enabled=True)
        response_payload = {
            "IMPORTRESULT": {
                "CREATED": "1",
                "ALTERED": "0",
                "DELETED": "0",
                "LASTVCHID": "0",
                "LASTMID": "1",
                "COMBINED": "0",
                "IGNORED": "0",
                "ERRORS": "0",
                "CANCELLED": "0",
                "EXCEPTIONS": "0",
                "LINEERROR": "",
            }
        }
        with patch(
            "tally_connector.routes._post_xml_import_result",
            new=AsyncMock(return_value=response_payload),
        ), patch(
            "tally_connector.routes._company_visible_in_list",
            new=AsyncMock(return_value=True),
        ):
            response = await company_create(
                {"NAME": "New Company", "STATE": "Tamil Nadu", "COUNTRY": "India"},
                company=None,
                x_company=None,
                x_agent_key="local-dev-key",
                settings=settings,
                client=object(),
            )

        self.assertEqual(response["status"], "ok")
        self.assertTrue(response["created"])
        self.assertEqual(response["verified_via"], "company_list")


class SecurityRolesTests(unittest.IsolatedAsyncioTestCase):
    async def test_security_roles_tdl_placeholder_response_is_rejected(self):
        settings = Settings(tdl_integration_enabled=True)
        with patch(
            "tally_connector.routes._tdl_feature_is_ready",
            return_value=True,
        ), patch(
            "tally_connector.routes._post_xml",
            new=AsyncMock(
                return_value={
                    "LINE": [
                        "This is a scaffold TDL package for the local connector.",
                    ]
                }
            ),
        ):
            with self.assertRaises(HTTPException) as ctx:
                await security_roles(
                    fetch=None,
                    limit=None,
                    view="summary",
                    company=None,
                    x_company=None,
                    x_agent_key="local-dev-key",
                    settings=settings,
                    client=object(),
                )

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["resource"], "/settings/security-roles")

    async def test_security_roles_experimental_write_is_blocked(self):
        settings = Settings(experimental_tally_security_roles_enabled=True)
        with self.assertRaises(HTTPException) as ctx:
            await security_roles_update(
                payload={"NAME": "Managers"},
                action="Alter",
                company=None,
                x_company=None,
                x_agent_key="local-dev-key",
                settings=settings,
                client=object(),
            )

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["error_type"], "unsupported")


class PayrollRouteTests(unittest.IsolatedAsyncioTestCase):
    async def test_export_tdl_payroll_route_rejects_scaffold_placeholder(self):
        settings = Settings(tdl_integration_enabled=True)
        with patch(
            "tally_connector.routes._post_xml",
            new=AsyncMock(
                return_value={
                    "LINE": [
                        "This is a scaffold TDL package for the local connector.",
                    ]
                }
            ),
        ):
            with self.assertRaises(HTTPException) as ctx:
                await _export_tdl_payroll_route(
                    object(),
                    settings,
                    "Acme",
                    "/employees",
                    "summary",
                )

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["required_tdl_feature"], "payroll_exports")

    async def test_employees_list_prefers_tdl_route_when_feature_ready(self):
        settings = Settings(tdl_integration_enabled=True)
        with patch(
            "tally_connector.routes._tdl_feature_is_ready",
            return_value=True,
        ), patch(
            "tally_connector.routes._export_tdl_payroll_route",
            new=AsyncMock(return_value={"items": [{"NAME": "Emp1"}]}),
        ) as export_tdl_route, patch(
            "tally_connector.routes._export_master",
            new=AsyncMock(return_value={"COSTCENTRE": []}),
        ) as export_master:
            response = await employees_list(
                fetch=None,
                filter_expr=None,
                limit=None,
                view="summary",
                company=None,
                x_company=None,
                x_agent_key="local-dev-key",
                settings=settings,
                client=object(),
            )

        self.assertEqual(response["items"][0]["NAME"], "Emp1")
        export_tdl_route.assert_awaited_once()
        export_master.assert_not_called()


class PriceLevelsTests(unittest.IsolatedAsyncioTestCase):
    async def test_export_price_levels_rejects_scaffold_tdl_response(self):
        settings = Settings(tdl_integration_enabled=True)
        with patch(
            "tally_connector.routes._tdl_feature_is_ready",
            return_value=True,
        ), patch(
            "tally_connector.routes._post_xml",
            new=AsyncMock(
                return_value={
                    "LINE": [
                        "This is a scaffold TDL package for the local connector.",
                    ]
                }
            ),
        ):
            with self.assertRaises(HTTPException) as ctx:
                await _export_price_levels(
                    object(),
                    "Acme",
                    fetch=None,
                    limit=None,
                    view="summary",
                    settings=settings,
                    route_path="/price-levels",
                )

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["required_tdl_feature"], "price_levels")

    async def test_price_structures_prefers_tdl_route_when_feature_ready(self):
        settings = Settings(tdl_integration_enabled=True)
        with patch(
            "tally_connector.routes._tdl_feature_is_ready",
            return_value=True,
        ), patch(
            "tally_connector.routes._post_xml",
            new=AsyncMock(return_value={"PRICELEVEL": [{"NAME": "Retail"}]}),
        ) as post_xml, patch(
            "tally_connector.routes._export_report",
            new=AsyncMock(return_value={"items": []}),
        ) as export_report:
            response = await price_structures(
                fetch=None,
                limit=None,
                view="summary",
                company=None,
                x_company=None,
                x_agent_key="local-dev-key",
                settings=settings,
                client=object(),
            )

        self.assertEqual(response["PRICELEVEL"][0]["NAME"], "Retail")
        post_xml.assert_awaited_once()
        export_report.assert_not_called()


class CommonComplianceRouteTests(unittest.TestCase):
    def test_validate_common_generate_voucher_type_returns_use_instead_guidance(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_common_generate_voucher_type("Purchase", "ewaybill")

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["error_type"], "unsupported")
        self.assertEqual(ctx.exception.detail["use_instead"], "/vouchers/sales/ewaybill/generate")
        self.assertIn("only implements", ctx.exception.detail["reason"])

    def test_validate_common_ready_voucher_type_returns_use_instead_guidance(self):
        with self.assertRaises(HTTPException) as ctx:
            _validate_common_ready_voucher_type("Journal", "einvoice")

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["error_type"], "unsupported")
        self.assertEqual(ctx.exception.detail["use_instead"], "/vouchers/sales/einvoice-ready")
        self.assertEqual(ctx.exception.detail["voucher_type"], "Journal")


class TdsOutstandingsTests(unittest.IsolatedAsyncioTestCase):
    async def test_tds_outstandings_rejects_scaffold_tdl_response(self):
        settings = Settings(tdl_integration_enabled=True)
        with patch(
            "tally_connector.routes._tdl_feature_is_ready",
            return_value=True,
        ), patch(
            "tally_connector.routes._post_xml",
            new=AsyncMock(
                return_value={
                    "LINE": [
                        "This is a scaffold TDL package for the local connector.",
                    ]
                }
            ),
        ):
            with self.assertRaises(HTTPException) as ctx:
                await report_tds_outstandings(
                    from_date=None,
                    to_date=None,
                    view="summary",
                    company=None,
                    x_company=None,
                    x_agent_key="local-dev-key",
                    settings=settings,
                    client=object(),
                )

        self.assertEqual(ctx.exception.status_code, 501)
        self.assertEqual(ctx.exception.detail["required_tdl_feature"], "tds_outstandings")


if __name__ == "__main__":
    unittest.main()
