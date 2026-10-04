"""Regression tests for the actual close-out verifier (standard library only)."""
import contextlib
import copy
from decimal import Decimal
import io
import json
from pathlib import Path
import unittest
from unittest import mock

import verify_chain as verifier


FIXTURES = Path(__file__).parent / "fixtures"


def v2_document(seq=1, prev=verifier.GENESIS):
    doc = {
        "seq": seq, "doc_id": f"test-{seq}", "type": "http", "@timestamp": "2026-10-04T00:00:00Z",
        "request": {"headers": "POST / HTTP/1.1\r\n\r\n", "body": "測試 😀", "body_len": 11},
        "response": {"body_b64": "AP8=", "body_truncated": True, "body_encoding": "base64"},
    }
    doc["integrity"] = {
        "scheme": verifier.SCHEME_V2, "algo": "sha256", "prev_hash": prev,
        "payload_sha256": verifier.payload_hash(doc),
    }
    doc["integrity"]["record_sha256"] = verifier.record_hash(doc, prev)
    return doc


class VerifyChainTest(unittest.TestCase):
    def verify(self, docs, **kwargs):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = verifier.verify(docs, **kwargs)
        return status, output.getvalue()

    def test_v2_chain_and_unicode_binary_truncation_metadata(self):
        first = v2_document()
        second = v2_document(2, first["integrity"]["record_sha256"])
        self.assertEqual(0, self.verify([first, second], strict=True, require_v2=True)[0])

    def test_modifying_any_stored_representation_fails(self):
        original = v2_document()
        changes = [
            ("request", "body", "forged"), ("request", "headers", "forged"),
            ("request", "body_len", 99), ("response", "body_b64", "QQ=="),
            ("response", "body_truncated", False), ("response", "body_encoding", "text"),
        ]
        for section, key, value in changes:
            with self.subTest(section=section, key=key):
                doc = copy.deepcopy(original)
                doc[section][key] = value
                self.assertEqual(1, self.verify([doc])[0])
        for key in ("@timestamp", "doc_id", "new_field"):
            with self.subTest(key=key):
                doc = copy.deepcopy(original)
                doc[key] = "forged"
                self.assertEqual(1, self.verify([doc])[0])

    def test_replacing_payload_digest_without_rechaining_fails(self):
        doc = v2_document()
        doc["request"]["body"] = "forged"
        doc["integrity"]["payload_sha256"] = verifier.payload_hash(doc)
        self.assertEqual(1, self.verify([doc])[0])

    def test_missing_unknown_or_downgraded_scheme_fails(self):
        for scheme in (None, "unknown-v9", verifier.LEGACY_SCHEME):
            with self.subTest(scheme=scheme):
                doc = v2_document()
                if scheme is None:
                    del doc["integrity"]["scheme"]
                else:
                    doc["integrity"]["scheme"] = scheme
                self.assertEqual(1, self.verify([doc])[0])

    def test_integrity_metadata_mutations_fail(self):
        for key, value in (("algo", "sha1"), ("payload_sha256", "f" * 64), ("extra", "forged")):
            with self.subTest(key=key):
                doc = v2_document()
                doc["integrity"][key] = value
                self.assertEqual(1, self.verify([doc])[0])

    def test_legacy_http_websocket_and_response_only_with_warning(self):
        for file in sorted(FIXTURES.glob("legacy-*.ndjson")):
            with self.subTest(file=file.name):
                doc = json.loads(file.read_text())
                status, output = self.verify([doc])
                self.assertEqual(0, status, output)
                self.assertIn("NOT authenticated", output)
                self.assertNotIn("altered after capture", output)
                self.assertEqual(1, self.verify([doc], require_v2=True)[0])

    def test_legacy_unprotected_payload_is_never_claimed_as_proven(self):
        doc = json.loads((FIXTURES / "legacy-normal.ndjson").read_text())
        doc["request"]["body"] = "old-format-cannot-detect-this"
        status, output = self.verify([doc])
        self.assertEqual(0, status)
        self.assertIn("NOT authenticated", output)

    def test_v2_can_continue_an_existing_legacy_chain(self):
        first = json.loads((FIXTURES / "legacy-normal.ndjson").read_text())
        second = v2_document(2, first["integrity"]["record_sha256"])
        status, output = self.verify([first, second], strict=True)
        self.assertEqual(0, status, output)
        self.assertIn("1 legacy v1 record", output)

    def test_missing_prefix_or_middle_is_incomplete_not_proof_of_upload_loss(self):
        first = v2_document()
        second = v2_document(2, first["integrity"]["record_sha256"])
        third = v2_document(3, second["integrity"]["record_sha256"])
        for docs in ([third], [first, third]):
            with self.subTest(count=len(docs)):
                status, output = self.verify(docs, strict=True)
                self.assertEqual(1, status)
                self.assertIn("cause unknown", output)
                self.assertIn("chain is incomplete", output)
                self.assertEqual(0, self.verify(docs)[0])

    def test_duplicate_sequence_and_empty_input_fail(self):
        doc = v2_document()
        self.assertEqual(1, self.verify([doc, doc])[0])
        self.assertEqual(1, self.verify([])[0])

    def test_malformed_metadata_is_reported_without_traceback(self):
        for bad in (None, [], {}, {"seq": "1"}, {"seq": True}, {"seq": 1, "integrity": None}):
            with self.subTest(doc=bad):
                self.assertEqual(1, self.verify([bad])[0])

    def test_object_order_and_number_spelling_are_not_content_changes(self):
        a = {"z": Decimal("1.2300e2"), "a": [Decimal("-0.00"), 10, True, None]}
        b = {"a": [0, Decimal("1e1"), True, None], "z": 123}
        self.assertEqual(verifier.payload_hash(a), verifier.payload_hash(b))
        self.assertNotEqual(verifier.payload_hash({"x": 1}), verifier.payload_hash({"x": "1"}))

    def test_shared_kotlin_python_canonical_vector(self):
        document = json.loads((FIXTURES / "canonical-v2.json").read_text(), parse_float=Decimal)
        expected = (FIXTURES / "canonical-v2.sha256").read_text().strip()
        self.assertEqual(expected, verifier.payload_hash(document))

    def test_partial_elasticsearch_results_cannot_be_reported_as_verified(self):
        for flags in ({"timed_out": True}, {"_shards": {"failed": 1}}):
            with self.subTest(flags=flags):
                response = io.BytesIO(json.dumps({"hits": {"hits": []}, **flags}).encode())
                with mock.patch.object(verifier.urllib.request, "urlopen", return_value=response):
                    with self.assertRaisesRegex(ValueError, "incomplete search"):
                        list(verifier.fetch_from_elasticsearch("https://example.test", "fake", "test-index"))


if __name__ == "__main__":
    unittest.main()
