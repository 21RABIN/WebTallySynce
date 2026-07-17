import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


from audit_connector_backend_parity import _extract_backend_routes  # noqa: E402


class BackendParityAuditTests(unittest.TestCase):
    def test_extract_backend_routes_reads_simple_mapping(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            controller_path = Path(tmpdir) / "SimpleController.java"
            controller_path.write_text(
                """
package com.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/demo")
public class SimpleController {
    @GetMapping("/items")
    public void items() {}
}
""".strip(),
                encoding="utf-8",
            )

            routes = _extract_backend_routes([controller_path])

        self.assertIn(("GET", "/api/demo/items"), routes)

    def test_extract_backend_routes_reads_value_and_consumes_mapping(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            controller_path = Path(tmpdir) / "XmlController.java"
            controller_path.write_text(
                """
package com.example;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/xml")
public class XmlController {
    @PostMapping(value = "/execute", consumes = {
            MediaType.APPLICATION_XML_VALUE,
            MediaType.TEXT_XML_VALUE
    })
    public void execute() {}
}
""".strip(),
                encoding="utf-8",
            )

            routes = _extract_backend_routes([controller_path])

        self.assertIn(("POST", "/api/xml/execute"), routes)


if __name__ == "__main__":
    unittest.main()
