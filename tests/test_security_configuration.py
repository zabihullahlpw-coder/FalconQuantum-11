from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

root = Path(__file__).parents[1]
ns='{http://schemas.android.com/apk/res/android}'

class SecuritySettingsTests(unittest.TestCase):
    def test_backup_and_cleartext_disabled(self):
        app = ET.parse(root/'AndroidManifest.xml').getroot().find('application')
        self.assertEqual(app.get(ns+'allowBackup'), 'false')
        self.assertEqual(app.get(ns+'usesCleartextTraffic'), 'false')
        self.assertEqual(app.get(ns+'dataExtractionRules'), '@xml/data_extraction_rules')
        self.assertIn('cleartextTrafficPermitted="false"',(root/'res/xml/network_security_config.xml').read_text())

    def test_no_plaintext_key_persistence_in_trader(self):
        code = (root/'src/com/falcon/quantum/Trader.java').read_text()
        self.assertIn('SecureKeys.save(', code)
        self.assertIn('SecureKeys.load(', code)
        self.assertNotIn('.putString(env + "_secret"', code)

    def test_keystore_encryption_and_migration_present(self):
        code = (root/'src/com/falcon/quantum/SecureKeys.java').read_text()
        for needle in ['AndroidKeyStore', 'AES/GCM/NoPadding', 'updateAAD', 'purgeLegacy', '.commit()']:
            self.assertIn(needle, code)

    def test_live_entries_inexorably_disabled(self):
        code = (root/'src/com/falcon/quantum/Trader.java').read_text()
        self.assertIn('LIVE entries disabled in Falcon 11.0 safety preview', code)
        self.assertRegex(code, r'public static boolean liveArmed\(Context ctx\) \{\s*return false;')
        ui = (root/'src/com/falcon/quantum/MainActivity.java').read_text()
        self.assertNotIn('Enable LIVE order sending (real funds)', ui)
        self.assertIn('securePcUrl(saved)', ui)
        self.assertIn('MIXED_CONTENT_NEVER_ALLOW', ui)

if __name__ == '__main__':
    unittest.main()
