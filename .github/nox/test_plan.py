import json
import tempfile
import unittest
from pathlib import Path

from plan import build_plan, main, parse_modules

LIST = """
# one module per line
src/pt/baozinho
  src/pt/mediocretoons

"""


class PlanTest(unittest.TestCase):
    def test_comments_blanks_and_spaces_are_ignored(self):
        self.assertEqual(["src/pt/baozinho", "src/pt/mediocretoons"], parse_modules(LIST))

    def test_a_module_must_look_like_src_lang_name(self):
        for bad in ["src/pt", "src/pt/a/b", "../src/pt/x", "src/pt/x;rm -rf", "src/PT/x", "src/pt/x y", "pt/baozinho", "src/pt/$(id)"]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                parse_modules(bad)

    def test_duplicates_are_an_error(self):
        with self.assertRaises(ValueError):
            parse_modules("src/pt/baozinho\nsrc/pt/baozinho")

    def test_an_empty_list_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_modules("# nothing\n\n")

    def test_matrix_entry_has_the_gradle_path_and_a_valid_artifact_name(self):
        plan = build_plan(["src/pt/baozinho"])

        self.assertEqual([{"module": "src/pt/baozinho", "gradle": ":src:pt:baozinho", "artifact": "pt.baozinho-apk"}], plan["matrix"])

    def test_github_outputs_carry_the_matrix_and_the_sparse_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            listing = Path(tmp) / "extensions.txt"
            output = Path(tmp) / "output"
            listing.write_text(LIST, encoding="utf-8")

            main(listing, output)

            text = output.read_text(encoding="utf-8")
            self.assertIn("matrix=" + json.dumps(build_plan(parse_modules(LIST))["matrix"], separators=(",", ":")), text)
            self.assertIn("paths<<", text)
            self.assertIn("src/pt/baozinho\nsrc/pt/mediocretoons\n", text)


if __name__ == "__main__":
    unittest.main()
