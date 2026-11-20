import json
import tempfile
import unittest
from pathlib import Path

from publish import extension_dirs, main, make_entry, merge_index, parse_fingerprint, single_fingerprint

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


def info_for(name, pkg, code):
    return {
        "packageName": f"eu.kanade.tachiyomi.extension.{pkg}",
        "name": name,
        "versionCode": code,
        "versionName": f"1.6.{code % 1000}",
        "contentWarning": 2,
        "sources": [{"id": 1, "name": name, "lang": "pt-BR", "baseUrl": f"https://{name.lower()}.com"}],
    }


def write_artifact(root, folder, info, with_apk=True):
    """Same layout upload-artifact produces: the module path is kept inside the artifact folder."""
    module = root / folder / "src" / info["packageName"].rsplit(".extension.", 1)[1].replace(".", "/")
    (module / "build" / "outputs" / "apk" / "release").mkdir(parents=True)
    (module / "res" / "mipmap-xxxhdpi").mkdir(parents=True)
    (module / "build" / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")
    (module / "res" / "mipmap-xxxhdpi" / "ic_launcher.png").write_bytes(b"icon-" + info["name"].encode())
    if with_apk:
        short = info["packageName"].removeprefix("eu.kanade.tachiyomi.extension.")
        (module / "build" / "outputs" / "apk" / "release" / f"tachiyomi-{short}-v{info['versionName']}.apk").write_bytes(b"apk-" + info["name"].encode())


class MultiExtensionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.artifacts = Path(self.tmp.name) / "artifacts"
        self.out = Path(self.tmp.name) / "repo"
        self.bao = info_for("Baozinho", "pt.baozinho", 106009)
        self.med = info_for("Mediocre Toons", "pt.mediocretoons", 106004)

    def publish(self, fingerprint=lambda apk: "a" * 64):
        main(self.artifacts, self.out, fingerprint_of=fingerprint)

    def test_extension_dirs_only_lists_folders_with_a_source_info(self):
        write_artifact(self.artifacts, "pt.mediocretoons-apk", self.med)
        write_artifact(self.artifacts, "pt.baozinho-apk", self.bao)
        (self.artifacts / "lixo").mkdir()
        (self.artifacts / "arquivo.txt").write_text("x")

        self.assertEqual(["pt.baozinho-apk", "pt.mediocretoons-apk"], [d.name for d in extension_dirs(self.artifacts)])

    def test_two_extensions_end_up_in_one_index_with_their_apks_and_icons(self):
        write_artifact(self.artifacts, "pt.baozinho-apk", self.bao)
        write_artifact(self.artifacts, "pt.mediocretoons-apk", self.med)

        self.publish()

        index = json.loads((self.out / "index.min.json").read_text(encoding="utf-8"))
        self.assertEqual([self.bao["packageName"], self.med["packageName"]], [e["pkg"] for e in index])
        self.assertEqual({"tachiyomi-pt.baozinho-v1.6.9.apk", "tachiyomi-pt.mediocretoons-v1.6.4.apk"}, {p.name for p in (self.out / "apk").iterdir()})
        self.assertEqual(b"icon-Mediocre Toons", (self.out / "icon" / f"{self.med['packageName']}.png").read_bytes())
        self.assertEqual(b"icon-Baozinho", (self.out / "icon" / f"{self.bao['packageName']}.png").read_bytes())
        self.assertEqual("a" * 64, json.loads((self.out / "repo.json").read_text(encoding="utf-8"))["meta"]["signingKeyFingerprint"])

    def test_publishing_one_extension_keeps_the_others(self):
        write_artifact(self.artifacts, "pt.baozinho-apk", self.bao)
        write_artifact(self.artifacts, "pt.mediocretoons-apk", self.med)
        self.publish()
        newer = info_for("Mediocre Toons", "pt.mediocretoons", 106005)
        for folder in self.artifacts.iterdir():
            for child in sorted(folder.rglob("*"), reverse=True):
                child.unlink() if child.is_file() else child.rmdir()
            folder.rmdir()
        write_artifact(self.artifacts, "pt.mediocretoons-apk", newer)

        self.publish()

        index = {e["pkg"]: e["code"] for e in json.loads((self.out / "index.min.json").read_text(encoding="utf-8"))}
        self.assertEqual({self.bao["packageName"]: 106009, self.med["packageName"]: 106005}, index)
        self.assertEqual({"tachiyomi-pt.baozinho-v1.6.9.apk", "tachiyomi-pt.mediocretoons-v1.6.5.apk"}, {p.name for p in (self.out / "apk").iterdir()})

    def test_different_signing_keys_publish_nothing(self):
        write_artifact(self.artifacts, "pt.baozinho-apk", self.bao)
        write_artifact(self.artifacts, "pt.mediocretoons-apk", self.med)
        keys = {"tachiyomi-pt.baozinho-v1.6.9.apk": "a" * 64}

        with self.assertRaises(ValueError):
            self.publish(fingerprint=lambda apk: keys.get(apk.name, "b" * 64))

        self.assertFalse(self.out.exists())

    def test_an_artifact_without_apk_is_an_error_and_publishes_nothing(self):
        write_artifact(self.artifacts, "pt.baozinho-apk", self.bao)
        write_artifact(self.artifacts, "pt.mediocretoons-apk", self.med, with_apk=False)

        with self.assertRaises(FileNotFoundError):
            self.publish()

        self.assertFalse(self.out.exists())

    def test_no_artifact_at_all_is_an_error(self):
        self.artifacts.mkdir()

        with self.assertRaises(FileNotFoundError):
            self.publish()

    def test_single_fingerprint_accepts_equal_and_rejects_mixed(self):
        self.assertEqual("a" * 64, single_fingerprint(["a" * 64, "a" * 64]))
        with self.assertRaises(ValueError):
            single_fingerprint(["a" * 64, "b" * 64])


if __name__ == "__main__":
    unittest.main()
