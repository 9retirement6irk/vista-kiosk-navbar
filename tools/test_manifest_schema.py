"""Check the manifest against the SDK's setting rules and the keys NavConfig reads."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / 'kiosk-satellite-plugin.json').read_text(encoding='utf-8'))
CONFIG = (ROOT / 'src/com/shymoose/ksnavbar/NavConfig.java').read_text(encoding='utf-8')


class ManifestSchemaTest(unittest.TestCase):
    def test_settings_follow_sdk_limits(self):
        settings = MANIFEST['settings']
        self.assertLessEqual(len(settings), 50)
        keys = [s['key'] for s in settings]
        self.assertEqual(len(keys), len(set(keys)))
        for setting in settings:
            with self.subTest(key=setting['key']):
                self.assertRegex(setting['key'], r'^[A-Za-z][A-Za-z0-9_]*$')
                self.assertIn(setting['type'], {'string', 'secret', 'boolean', 'number', 'color', 'select', 'entity'})
                if setting['type'] == 'string':
                    self.assertLessEqual(len(setting['default']), 512)
                if setting['type'] == 'select':
                    self.assertLessEqual(len(setting['options']), 32)
                    self.assertIn(setting['default'], setting['options'])
                if setting['type'] == 'number':
                    self.assertTrue(setting['min'] <= setting['default'] <= setting['max'])
                    self.assertEqual((setting['default'] - setting['min']) % setting['step'], 0)

    def test_item_slots_match_navconfig(self):
        slots = int(re.search(r'static final int SLOTS = (\d+);', CONFIG).group(1))
        keys = {s['key'] for s in MANIFEST['settings']}
        for slot in range(1, slots + 1):
            for field in ('Label', 'Icon', 'IconSelected', 'Target'):
                self.assertIn(f'item{slot}{field}', keys)
        self.assertNotIn(f'item{slots + 1}Label', keys)
        for key in ('position', 'labels', 'hideOn', 'scale', 'opacity', 'collapse', 'autoCollapse'):
            self.assertIn(key, keys)
            self.assertIn(f'"{key}"', CONFIG)

    def test_select_options_are_the_ones_navconfig_understands(self):
        by_key = {s['key']: s for s in MANIFEST['settings']}
        self.assertEqual(by_key['position']['options'], ['Bottom', 'Top'])
        self.assertEqual(by_key['labels']['options'], ['Always', 'Active only', 'Never'])
        self.assertEqual(by_key['collapse']['options'], ['Off', 'Start open', 'Start collapsed'])
        for option in by_key['scale']['options']:
            self.assertRegex(option, r'^[0-9]+%$')

    def test_commands_and_capabilities(self):
        for command in MANIFEST['commands']:
            self.assertRegex(command['id'], r'^[a-z][a-zA-Z0-9]*$')
        self.assertEqual(set(MANIFEST['capabilities']), {'overlay', 'host.read', 'host.control'})
        self.assertGreaterEqual(MANIFEST['minAndroidSdk'], 26)  # java.util.Base64


if __name__ == '__main__':
    unittest.main()
