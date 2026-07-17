import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = PROJECT_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))


from tally_connector.tally_client import (  # noqa: E402
    candidate_base_urls,
    trace_label_from_xml,
    trace_slug,
    write_trace_snapshot,
)


class TallyClientTraceTests(unittest.TestCase):
    def test_trace_label_prefers_id(self):
        xml = "<ENVELOPE><HEADER><ID>Form 24Q</ID></HEADER></ENVELOPE>"
        self.assertEqual(trace_label_from_xml(xml), "Form 24Q")

    def test_trace_slug_normalizes_text(self):
        self.assertEqual(trace_slug("Form 24Q"), "form-24q")
        self.assertEqual(trace_slug("  "), "tally-request")

    def test_write_trace_snapshot_creates_expected_files(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            trace_dir = Path(tmpdir)
            entry_dir = write_trace_snapshot(
                trace_dir,
                "<ENVELOPE><HEADER><ID>Balance Sheet</ID></HEADER></ENVELOPE>",
                response_text="<ENVELOPE/>",
                status_code=200,
            )
            self.assertTrue((entry_dir / "request.xml").is_file())
            self.assertTrue((entry_dir / "response.xml").is_file())
            self.assertTrue((entry_dir / "meta.json").is_file())

    def test_candidate_base_urls_adds_ipv6_loopback_for_ipv4_localhost(self):
        self.assertEqual(
            candidate_base_urls("http://127.0.0.1:9000"),
            [
                "http://127.0.0.1:9000",
                "http://[::1]:9000",
                "http://localhost:9000",
            ],
        )

    def test_candidate_base_urls_adds_ipv4_and_ipv6_for_localhost(self):
        self.assertEqual(
            candidate_base_urls("http://localhost:9000"),
            [
                "http://localhost:9000",
                "http://[::1]:9000",
                "http://127.0.0.1:9000",
            ],
        )


if __name__ == "__main__":
    unittest.main()
