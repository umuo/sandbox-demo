"""Compare the baseline and working output capture in bounded, separate JVM forks.

No Maven dependencies/downloads. Build target/classes first; JAVA_HOME selects the real JDK.
All generated classes and JSON stay in the workspace target/output-benchmark directory.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = Path('io/github/sandboxdemo/core')


def run(command, **options):
    return subprocess.run(command, check=True, text=True, capture_output=True, **options).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='eedbf5c')
    parser.add_argument('--forks', type=int, default=3, choices=range(1, 6))
    args = parser.parse_args()
    java_home = Path(os.environ['JAVA_HOME'])
    java, javac = str(java_home / 'bin/java'), str(java_home / 'bin/javac')
    target = ROOT / 'target/output-benchmark'
    baseline, bench = target / 'baseline', target / 'benchmark'
    baseline.mkdir(parents=True, exist_ok=True)
    bench.mkdir(parents=True, exist_ok=True)
    source = target / 'baseline-src' / PACKAGE
    source.mkdir(parents=True, exist_ok=True)
    revision = run(['git', 'rev-parse', '--verify', args.baseline + '^{commit}'], cwd=ROOT).strip()
    files = []
    for name in ('LimitedOutput.java', 'OutputCallbacks.java'):
        path = source / name
        path.write_text(run(['git', 'show', revision + ':src/main/java/' + (PACKAGE / name).as_posix()], cwd=ROOT))
        files.append(str(path))
    version = subprocess.run([java, '-version'], check=True, text=True, capture_output=True).stderr.strip()
    flags = ['-source', '8', '-target', '8'] if 'version "1.8.' in version else ['--release', '8']
    classes = str(ROOT / 'target/classes')
    run([javac] + flags + ['-encoding', 'UTF-8', '-cp', classes, '-d', str(baseline)] + files)
    run([javac] + flags + ['-encoding', 'UTF-8', '-cp', classes, '-d', str(bench), str(ROOT / 'src/test/java' / PACKAGE / 'OutputAllocationBenchmark.java')])
    report = {'baseline': revision, 'working_head': run(['git', 'rev-parse', 'HEAD'], cwd=ROOT).strip(), 'working_diff': run(['git', 'diff', '--', 'src/main/java/io/github/sandboxdemo/core/LimitedOutput.java', 'src/main/java/io/github/sandboxdemo/core/OutputCallbacks.java'], cwd=ROOT), 'java': version, 'os': platform.platform(), 'heap': '-Xms128m -Xmx128m -XX:+UseSerialGC', 'samples': []}
    for total in (8, 32):
        for mode in ('none', 'byte', 'text', 'both'):
            for fork in range(args.forks):
                # Alternate order to reduce a consistent first/second-run bias.
                variants = ('baseline', 'working') if fork % 2 == 0 else ('working', 'baseline')
                for variant in variants:
                    cp = [str(bench), classes] if variant == 'working' else [str(bench), str(baseline), classes]
                    raw = run([java, '-Xms128m', '-Xmx128m', '-XX:+UseSerialGC', '-cp', os.pathsep.join(cp), 'io.github.sandboxdemo.core.OutputAllocationBenchmark', mode, str(total)])
                    sample = dict(json.loads(raw), variant=variant, mode=mode, input_mib=total, fork=fork)
                    report['samples'].append(sample)
                    print(json.dumps(sample), flush=True)
                    (target / 'results.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
