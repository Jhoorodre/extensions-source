import unittest

from publish import make_entry, merge_index, parse_fingerprint

INFO = {
    "packageName": "eu.kanade.tachiyomi.extension.pt.baozinho",
    "name": "Baozinho",
    "versionCode": 106001,
    "versionName": "1.6.1",
    "contentWarning": 2,
    "sources": [{"id": 3067506059118108232, "name": "Baozinho", "lang": "pt-BR", "baseUrl": "https://baozinho.com"}],
}


class PublishTest(unittest.TestCase):
    def test_entry_uses_mihon_legacy_fields(self):
        entry = make_entry(INFO, "tachiyomi-pt.baozinho-v1.6.1.apk")

        self.assertEqual("pt-BR", entry["lang"])
        self.assertEqual(106001, entry["code"])
        self.assertEqual("1.6.1", entry["version"])
        self.assertEqual(3067506059118108232, entry["sources"][0]["id"])

    def test_mixed_is_not_nsfw_but_nsfw_is(self):
        self.assertEqual(0, make_entry(INFO, "a.apk")["nsfw"])
        self.assertEqual(1, make_entry({**INFO, "contentWarning": 3}, "a.apk")["nsfw"])

    def test_fingerprint_comes_from_the_sha256_line(self):
        digest = "596588d0e1d7135bc6accba226c84e79078fcdd71609422f4efccb65af0d80f9"
        out = f"V2 Signer: certificate DN: CN=x\nV2 Signer: certificate SHA-256 digest: {digest}\nV2 Signer: certificate SHA-1 digest: {'a' * 40}\n"

        self.assertEqual(digest, parse_fingerprint(out))

    def test_fingerprint_missing_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_fingerprint("Not a signed jar file")

    def test_merge_replaces_same_package_and_keeps_others(self):
        old = [{"pkg": "x.other", "code": 1}, {"pkg": INFO["packageName"], "code": 106001}]
        new = make_entry({**INFO, "versionCode": 106002}, "b.apk")

        merged = merge_index(old, new)

        self.assertEqual(["eu.kanade.tachiyomi.extension.pt.baozinho", "x.other"], [e["pkg"] for e in merged])
        self.assertEqual(106002, merged[0]["code"])


if __name__ == "__main__":
    unittest.main()
