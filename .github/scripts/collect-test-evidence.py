"""Summarize explicit platform metadata and JUnit counts without output or diagnostics."""
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

reports = sorted(Path('target/surefire-reports').glob('TEST-*.xml'))
counts = dict(discovered=0, executed=0, failures=0, errors=0, skipped=0)
runtime = {}
for report in reports:
    suite = ET.parse(report).getroot()
    tests = int(suite.get('tests', 0))
    skipped = int(suite.get('skipped', 0))
    counts['discovered'] += tests
    counts['executed'] += tests - skipped
    counts['skipped'] += skipped
    counts['failures'] += int(suite.get('failures', 0))
    counts['errors'] += int(suite.get('errors', 0))
    for prop in suite.findall('./properties/property'):
        name = prop.get('name')
        if name in {'os.name', 'os.version', 'os.arch', 'java.version', 'java.vendor'}:
            runtime[name] = prop.get('value')
backend = []
allowed = {'backend', 'backend.version', 'sdk.version', 'os.name', 'os.version', 'os.arch',
           'java.version', 'java.vendor', 'scope', 'read.policy', 'network.policy', 'probe.ready'}
for file in sorted(Path('target/backend-evidence').glob('*.xml')):
    backend.append({entry.get('key'): entry.text for entry in ET.parse(file).getroot().findall('entry')
                    if entry.get('key') in allowed})
result = {'reports_available': bool(reports), 'counts': counts, 'runtime': runtime,
          'native_preflight_evidence': backend,
          'note': 'Skipped tests are not passes. Preflight evidence is not a VM or complete cleanup guarantee.'}
Path('target').mkdir(exist_ok=True)
Path('target/test-evidence.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
summary = os.environ.get('GITHUB_STEP_SUMMARY')
if summary:
    with open(summary, 'a', encoding='utf-8') as output:
        output.write('### Sandbox test evidence\n\n```json\n' + json.dumps(result, ensure_ascii=False, indent=2) + '\n```\n')
print(json.dumps({'reports_available': bool(reports), 'counts': counts}))
