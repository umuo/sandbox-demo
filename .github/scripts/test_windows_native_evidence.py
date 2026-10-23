"""Synthetic JUnit evidence: missing and skipped native tests must fail closed."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    'native_gate', Path(__file__).with_name('check-windows-native-evidence.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class NativeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, backend='default'):
        required = json.loads(GATE.MANIFEST.read_text())
        reports = self.root / 'surefire-reports'
        reports.mkdir()
        for name, methods in {**required['shared'], **required[backend]}.items():
            suite = ET.Element('testsuite')
            props = ET.SubElement(suite, 'properties')
            for key, value in {'os.name': 'Windows synthetic', 'os.arch': 'amd64',
                               'java.version': '1.8.synthetic', 'java.vendor': 'fixture'}.items():
                ET.SubElement(props, 'property', name=key, value=value)
            for method in methods:
                ET.SubElement(suite, 'testcase', classname='fixture.' + name, name=method)
            ET.ElementTree(suite).write(reports / ('TEST-' + name + '.xml'))
        probes = self.root / 'backend-evidence'
        probes.mkdir()
        values = ET.Element('properties')
        for key, value in {'backend': GATE.BACKENDS[backend], 'probe.ready': 'true',
                           'windows.token.elevated': 'false' if backend == 'default' else 'true'}.items():
            ET.SubElement(values, 'entry', key=key).text = value
        ET.ElementTree(values).write(probes / 'WINDOWS.xml')

    def first_report(self):
        return next((self.root / 'surefire-reports').glob('TEST-*.xml'))

    def test_complete_default_and_production_evidence_passes(self):
        for backend in GATE.BACKENDS:
            with self.subTest(backend=backend), tempfile.TemporaryDirectory() as folder:
                previous = self.root
                self.root = Path(folder)
                self.fixture(backend)
                result = GATE.check(backend, self.root)
                self.assertTrue(result['passed'], result)
                self.assertEqual(result['required'], result['executed_successfully'])
                self.root = previous

    def test_no_reports_is_not_success(self):
        result = GATE.check('default', self.root)
        self.assertFalse(result['passed'])
        self.assertEqual(0, result['executed_successfully'])

    def test_skip_failure_and_error_are_rejected(self):
        self.fixture()
        path = self.first_report()
        for tag in ('skipped', 'failure', 'error'):
            with self.subTest(tag=tag):
                tree = ET.parse(path)
                node = ET.SubElement(tree.getroot().find('testcase'), tag)
                tree.write(path)
                self.assertFalse(GATE.check('default', self.root)['passed'])
                tree.getroot().find('testcase').remove(node)
                tree.write(path)

    def test_missing_method_and_report_are_rejected(self):
        self.fixture()
        path = self.first_report()
        tree = ET.parse(path)
        tree.getroot().remove(tree.getroot().find('testcase'))
        tree.write(path)
        self.assertFalse(GATE.check('default', self.root)['passed'])
        path.unlink()
        self.assertFalse(GATE.check('default', self.root)['passed'])

    def test_wrong_backend_or_elevated_default_probe_is_rejected(self):
        self.fixture()
        path = self.root / 'backend-evidence/WINDOWS.xml'
        original = path.read_text()
        for text in (original.replace('windows-restricted-token', GATE.BACKENDS['production']),
                     original.replace('>false<', '>true<'),
                     original.replace('key="probe.ready">true', 'key="probe.ready">false')):
            path.write_text(text)
            self.assertFalse(GATE.check('default', self.root)['passed'])

    def test_malformed_report_is_rejected(self):
        self.fixture()
        self.first_report().write_text('<invalid')
        with self.assertRaises(ET.ParseError):
            GATE.check('default', self.root)

    def test_duplicate_method_is_rejected(self):
        self.fixture()
        path = self.first_report()
        tree = ET.parse(path)
        case = tree.getroot().find('testcase')
        ET.SubElement(tree.getroot(), 'testcase', case.attrib)
        tree.write(path)
        self.assertFalse(GATE.check('default', self.root)['passed'])


if __name__ == '__main__':
    unittest.main()
