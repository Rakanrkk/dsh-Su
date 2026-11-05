"""Unit tests for `rtbuild.py` bundled-plugin copying and Root-only overlay rows."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
RTBUILD = HERE.parent / "lib" / "rtbuild.py"
spec = importlib.util.spec_from_file_location("rtbuild", RTBUILD)
rtbuild = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rtbuild)


def manifest(tmp, entry="./lib/index.mjs"):
    directory = tmp / "dsh-su-tools"
    directory.mkdir()
    (directory / "lib").mkdir()
    (directory / "lib" / "index.mjs").write_text("export {};\n", encoding="utf-8")
    (directory / "package.json").write_text(json.dumps(
        {"name": "dsh-su-tools", "version": "0.1.0", "main": entry,
         "exports": {".": entry}, "files": ["lib", "package.json", entry]}), encoding="utf-8")
    return directory


class BundledPluginTest(unittest.TestCase):
    def test_declared_entry_and_version_are_used(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = manifest(Path(temporary))
            name, version, entry = rtbuild.bundled_plugins(source)
            self.assertEqual((name, version, entry), ("dsh-su-tools", "0.1.0", "./lib/index.mjs"))

    def test_missing_declared_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "dsh-su-tools").mkdir()
            (root / "dsh-su-tools" / "package.json").write_text(
                '{"name":"dsh-su-tools","version":"0.1.0","exports":{}}')
            with self.assertRaises(ValueError):
                rtbuild.bundled_plugins(root / "dsh-su-tools")

    def test_standard_main_is_used_without_private_dsh_entry(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = manifest(Path(temporary))
            text = json.loads(source.joinpath("package.json").read_text(encoding="utf-8"))
            del text["exports"]
            text["main"] = "lib/index.js"
            source.joinpath("lib", "index.js").write_text("export {};\n", encoding="utf-8")
            source.joinpath("package.json").write_text(json.dumps(text), encoding="utf-8")
            self.assertEqual(rtbuild.bundled_plugins(source)[2], "lib/index.js")

    def test_production_files_expand_directories_and_exclude_tests_and_dist(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = manifest(Path(temporary))
            (source / "lib" / "tool.js").write_text("export {};\n", encoding="utf-8")
            (source / "test").mkdir()
            (source / "dist").mkdir()
            (source / "test" / "plugin.test.js").write_text("test;\n", encoding="utf-8")
            (source / "dist" / "plugin.tgz").write_bytes(b"package")
            files = rtbuild.production_files(source)
            self.assertEqual(files, ("lib/index.mjs", "lib/tool.js", "package.json"))

    def test_plugin_entries_only_include_production_files_and_peer_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = manifest(Path(temporary))
            (source / "lib" / "tool.js").write_text("export {};\n", encoding="utf-8")
            (source / "test").mkdir()
            (source / "test" / "plugin.test.js").write_text("test;\n", encoding="utf-8")
            (source / "dist").mkdir()
            (source / "node_modules").mkdir()
            (source / "dist" / "plugin.tgz").write_bytes(b"package")
            (source / "node_modules" / "leftover.js").write_text("export {};\n", encoding="utf-8")
            text = json.loads((source / "package.json").read_text(encoding="utf-8"))
            text["peerDependencies"] = {"@deepseek-ai/dsh-tools": ">=0.2.1-rc.2 <0.3.0-0"}
            (source / "package.json").write_text(json.dumps(text), encoding="utf-8")
            entries = {}
            rtbuild.add_plugin_files(entries, source, "opt/dsh-go/dsh-su-tools")
            self.assertEqual(sorted(entries), [
                "opt/dsh-go/dsh-su-tools/lib/index.mjs",
                "opt/dsh-go/dsh-su-tools/lib/tool.js",
                "opt/dsh-go/dsh-su-tools/node_modules/@deepseek-ai/dsh-tools",
                "opt/dsh-go/dsh-su-tools/package.json",
            ])
            self.assertEqual(entries["opt/dsh-go/dsh-su-tools/node_modules/@deepseek-ai/dsh-tools"].link,
                             "/opt/dsh/node_modules/@deepseek-ai/dsh-tools")

    def test_overlay_rows_use_package_id_and_absolute_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = manifest(Path(temporary))
            rows = rtbuild.plugin_rows([("/opt/dsh-go/dsh-su-tools", source)])
            self.assertEqual(rows, (("dsh-su-tools", "/opt/dsh-go/dsh-su-tools/lib/index.mjs"),))


if __name__ == "__main__":
    unittest.main()
