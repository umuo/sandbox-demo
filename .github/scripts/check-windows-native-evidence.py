"""Fail when a required native method is missing, skipped, failed or run as the wrong backend."""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

MANIFEST = Path(__file__).with_name('windows-native-required.json')
BACKENDS = {'default': 'windows-restricted-token',
            'production': 'windows-dedicated-user-restricted-token'}


def check(backend, directory):
    required = json.loads(MANIFEST.read_text(encoding='utf-8'))
    expected = {**required['shared'], **required[backend]}
    cases = {}
    runtime = {}
    errors = []
    for report in sorted((directory / 'surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(report).getroot()
        props = {p.get('name'): p.get('value') for p in suite.findall('./properties/property')}
        for key in ('os.name', 'os.arch', 'java.version', 'java.vendor'):
            if props.get(key):
                runtime[key] = props[key]
        for case in suite.findall('testcase'):
            key = (case.get('classname', '').rsplit('.', 1)[-1], case.get('name'))
            if key in cases:
                errors.append('duplicate method: ' + '.'.join(key))
            cases[key] = case
    executed = 0
    for suite, methods in expected.items():
        for method in methods:
            case = cases.get((suite, method))
            if case is None:
                errors.append('missing: ' + suite + '.' + method)
            elif any(case.find(tag) is not None for tag in ('skipped', 'failure', 'error')):
                errors.append('not successful: ' + suite + '.' + method)
            else:
                executed += 1
    if not runtime.get('os.name', '').startswith('Windows') or len(runtime) != 4:
        errors.append('missing or non-Windows runtime metadata')
    matched = False
    for path in sorted((directory / 'backend-evidence').glob('*.xml')):
        values = {v.get('key'): v.text for v in ET.parse(path).getroot().findall('entry')}
        if (values.get('backend') == BACKENDS[backend]
                and values.get('probe.ready') == 'true'
                and values.get('windows.token.elevated') == ('false' if backend == 'default' else 'true')):
            matched = True
    if not matched:
        errors.append('missing successful probe with expected backend and token elevation')
    result = {'backend': BACKENDS[backend],
              'identity': 'standard-user' if backend == 'default' else 'elevated-installation',
              'required': sum(map(len, expected.values())), 'executed_successfully': executed,
              'runtime': runtime, 'passed': not errors, 'errors': errors}
    result['counts'] = {
        'discovered': len(cases),
        'executed': sum(c.find('skipped') is None for c in cases.values()),
        'skipped': sum(c.find('skipped') is not None for c in cases.values()),
        'failures': sum(c.find('failure') is not None for c in cases.values()),
        'errors': sum(c.find('error') is not None for c in cases.values()),
    }
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'native-acceptance.json').write_text(
        json.dumps(result, indent=2), encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', required=True, choices=BACKENDS)
    parser.add_argument('--directory', type=Path, default=Path('target'))
    args = parser.parse_args()
    try:
        result = check(args.backend, args.directory)
    except (ET.ParseError, OSError, ValueError) as error:
        parser.exit(1, 'Invalid native acceptance evidence: ' + type(error).__name__ + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
