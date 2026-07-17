import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = PROJECT_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))


from tally_connector.xml_builder import build_voucher_upsert  # noqa: E402


class XmlBuilderVoucherTests(unittest.TestCase):
    def test_accounting_voucher_create_preserves_allledger_shape_without_duplication(self):
        xml = build_voucher_upsert(
            "Ridsys",
            "Create",
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Journal",
                "VOUCHERNUMBER": "J-1",
                "ALLLEDGERENTRIES.LIST": [
                    {"LEDGERNAME": "ramf", "AMOUNT": "10.00", "ISDEEMEDPOSITIVE": "No"},
                    {"LEDGERNAME": "Sales Account", "AMOUNT": "-10.00", "ISDEEMEDPOSITIVE": "Yes"},
                ],
            },
            "Journal",
        )
        self.assertEqual(xml.count("<ALLLEDGERENTRIES.LIST>"), 2)
        self.assertNotIn("<LEDGERENTRIES.LIST>", xml)

    def test_accounting_voucher_create_preserves_ledgerentries_shape_without_duplication(self):
        xml = build_voucher_upsert(
            "Ridsys",
            "Create",
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Payment",
                "VOUCHERNUMBER": "P-1",
                "LEDGERENTRIES.LIST": [
                    {"LEDGERNAME": "Raj traders", "AMOUNT": "-10.00", "ISDEEMEDPOSITIVE": "Yes"},
                    {"LEDGERNAME": "Cash", "AMOUNT": "10.00", "ISDEEMEDPOSITIVE": "No"},
                ],
            },
            "Payment",
        )
        self.assertEqual(xml.count("<LEDGERENTRIES.LIST>"), 2)
        self.assertNotIn("<ALLLEDGERENTRIES.LIST>", xml)

    def test_accounting_voucher_ledger_fields_emit_flags_before_amount(self):
        xml = build_voucher_upsert(
            "Ridsys",
            "Create",
            {
                "DATE": "20260402",
                "VOUCHERTYPENAME": "Receipt",
                "VOUCHERNUMBER": "R-1",
                "ALLLEDGERENTRIES.LIST": [
                    {
                        "LEDGERNAME": "Cash",
                        "AMOUNT": "-10.00",
                        "ISDEEMEDPOSITIVE": "Yes",
                    }
                ],
            },
            "Receipt",
        )
        self.assertIn(
            "<LEDGERNAME>Cash</LEDGERNAME>\n          <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>\n          <AMOUNT>-10.00</AMOUNT>",
            xml,
        )


if __name__ == "__main__":
    unittest.main()
