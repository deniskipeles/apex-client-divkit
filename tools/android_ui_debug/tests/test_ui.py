import json
import tempfile
import unittest
from pathlib import Path

from tools.android_ui_debug.apk import ApkError, parse_badging
from tools.android_ui_debug.scenario import ScenarioRunner, load_scenario
from tools.android_ui_debug.ui import Bounds, find_nodes, parse_ui_xml

SAMPLE = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
        package="com.example" content-desc="" clickable="false" enabled="true"
        focused="false" selected="false" bounds="[0,0][1080,1920]">
    <node index="0" text="Libsignal E2EE" resource-id="com.example:id/nav_libsignal"
          class="android.widget.Button" package="com.example" content-desc="Open card"
          clickable="true" enabled="true" focused="false" selected="false"
          bounds="[100,200][500,300]" />
    <node index="1" text="Status: DECRYPTED" resource-id=""
          class="android.widget.TextView" package="com.example" content-desc=""
          clickable="false" enabled="true" focused="false" selected="false"
          bounds="[100,400][900,460]" />
    <node index="2" text="" resource-id="com.example:id/input"
          class="android.widget.EditText" package="com.example" content-desc="Search"
          clickable="true" enabled="true" focused="true" selected="false"
          bounds="[100,600][900,700]" />
    <node index="3" text="No bounds" resource-id="" class="android.widget.TextView"
          package="com.example" content-desc="" clickable="false" enabled="true"
          focused="false" selected="false" />
  </node>
</hierarchy>
"""


class UiParserTest(unittest.TestCase):
    def setUp(self):
        self.nodes = parse_ui_xml(SAMPLE)

    def test_parses_all_nodes(self):
        self.assertEqual(5, len(self.nodes))

    def test_bounds_and_center(self):
        node = find_nodes(self.nodes, text="Libsignal E2EE")[0]
        self.assertEqual(Bounds(100, 200, 500, 300), node.bounds)
        self.assertEqual((300, 250), node.center)
        self.assertEqual(400, node.bounds.width)
        self.assertEqual(100, node.bounds.height)

    def test_find_by_text_contains_regex_desc_and_id(self):
        self.assertEqual(1, len(find_nodes(self.nodes, text="Libsignal E2EE")))
        self.assertEqual(1, len(find_nodes(self.nodes, contains="DECRYPTED")))
        self.assertEqual(1, len(find_nodes(self.nodes, regex=r"Status: \w+")))
        self.assertEqual(1, len(find_nodes(self.nodes, desc="Search")))
        self.assertEqual(1, len(find_nodes(self.nodes, resource_id="com.example:id/input")))

    def test_clickable_filter(self):
        clickable = find_nodes(self.nodes, clickable=True)
        self.assertEqual(2, len(clickable))
        self.assertTrue(all(n.clickable for n in clickable))

    def test_node_without_bounds_is_excluded_by_default(self):
        self.assertEqual(0, len(find_nodes(self.nodes, text="No bounds")))
        self.assertEqual(1, len(find_nodes(self.nodes, text="No bounds", require_bounds=False)))

    def test_label_prefers_text_then_desc(self):
        self.assertEqual("Libsignal E2EE", find_nodes(self.nodes, text="Libsignal E2EE")[0].label)
        self.assertEqual("Search", find_nodes(self.nodes, desc="Search")[0].label)

    def test_empty_dump_is_empty_list(self):
        self.assertEqual([], parse_ui_xml(""))


class ApkBadgingTest(unittest.TestCase):
    SAMPLE = """package: name='com.aistudio.sduiposengine.kmpdiv' versionCode='42' versionName='1.2.3'
launchable-activity: name='com.example.MainActivity'  label='' icon=''
"""

    def test_parses_package_activity_and_version(self):
        info = parse_badging(self.SAMPLE, "app.apk")
        self.assertEqual("com.aistudio.sduiposengine.kmpdiv", info.package)
        self.assertEqual("com.example.MainActivity", info.activity)
        self.assertEqual("1.2.3", info.version_name)
        self.assertEqual("42", info.version_code)

    def test_rejects_output_without_a_package(self):
        with self.assertRaises(ApkError):
            parse_badging("application-label:'Apex'")


class ScenarioConfigTest(unittest.TestCase):
    def test_selector_mapping(self):
        runner = ScenarioRunner(adb=None, scenario={"package": "p", "steps": []})  # type: ignore[arg-type]
        selectors = runner._selectors(
            {"text": "Embed Item Name", "id": "x", "desc": "y", "clickable": "true"}
        )
        self.assertEqual(
            {"text": "Embed Item Name", "resource_id": "x", "desc": "y", "clickable": True},
            selectors,
        )

    def test_load_scenario_requires_package_and_steps(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = Path(tmp) / "good.json"
            good.write_text(json.dumps({"package": "p", "steps": [{"action": "launch"}]}), encoding="utf-8")
            self.assertEqual("p", load_scenario(good)["package"])

            bad = Path(tmp) / "bad.json"
            bad.write_text(json.dumps({"steps": []}), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_scenario(bad)


if __name__ == "__main__":
    unittest.main()
