#!/usr/bin/env python3
"""Focused inactive-proposal checks; no APK installation or network requests."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parents[1]


class BackgroundCheckinProposalTests(unittest.TestCase):
    def test_job_ownership_and_foreground_network_invariants(self):
        ecj = Path(os.environ.get('INHOUSE_ECJ', '/workspace/ig-calma/tools/ecj.jar'))
        if not ecj.is_file():
            self.fail('Set INHOUSE_ECJ to the repository ECJ compiler to run proposal checks')
        with tempfile.TemporaryDirectory(prefix='inhouse-checkin-test-') as temporary:
            work = Path(temporary)
            clock = work / 'SystemClock.java'
            # Compile-only test double; never copied into an Android artifact.
            clock.write_text('package android.os; public final class SystemClock { '
                             'public static long elapsedRealtime() { return System.nanoTime()/1000000L; }}')
            runtime = HERE / 'runtime/inhouse/microg/bridge'
            subprocess.run(['java', '-jar', str(ecj), '-17', '-proc:none', '-d', str(work),
                str(clock), str(runtime / 'JobOwnershipPolicy.java'),
                str(runtime / 'CheckinJobNetwork.java'), str(runtime / 'ForegroundSessionPolicy.java'), str(HERE / 'tests/ProposalPolicyTest.java')],
                check=True, capture_output=True, text=True)
            result = subprocess.run(['java', '-cp', str(work), 'inhouse.microg.bridge.ProposalPolicyTest'],
                                    check=True, capture_output=True, text=True)
            self.assertIn('registration proposal invariants passed', result.stdout)

    def test_runtime_compiles_without_packaging_compile_only_stubs(self):
        ecj = Path(os.environ.get('INHOUSE_ECJ', '/workspace/ig-calma/tools/ecj.jar'))
        sdk = Path(os.environ.get('INHOUSE_ANDROID_JAR', '/workspace/ig-calma/tools/android-35/android.jar'))
        self.assertTrue(ecj.is_file() and sdk.is_file(), 'Set INHOUSE_ECJ and INHOUSE_ANDROID_JAR')
        with tempfile.TemporaryDirectory(prefix='inhouse-checkin-compile-') as temporary:
            work = Path(temporary)
            stubs, runtime = work / 'stubs', work / 'runtime'
            stubs.mkdir(); runtime.mkdir()
            def compile_to(destination, classpath, sources):
                subprocess.run(['java', '-jar', str(ecj), '-1.8', '-proc:none',
                    '-classpath', classpath, '-d', str(destination), *map(str, sources)],
                    check=True, capture_output=True, text=True)
            compile_to(stubs, str(sdk), sorted((HERE / 'stubs').rglob('*.java')))
            compile_to(runtime, str(sdk) + os.pathsep + str(stubs), sorted((HERE / 'runtime').rglob('*.java')))
            classes = {str(path.relative_to(runtime)) for path in runtime.rglob('*.class')}
            self.assertTrue(classes)
            self.assertNotIn('inhouse/microg/bridge/BackendEnvironment.class', classes)
            self.assertFalse(any(name.startswith('org/microg/') for name in classes))
            for name in ('CheckinJobService', 'CheckinJobScheduler', 'CheckinJobNetwork', 'JobOwnershipPolicy', 'McsSessionCoordinator', 'ForegroundSessionPolicy'):
                self.assertIn('inhouse/microg/bridge/' + name + '.class', classes)

    def test_preparer_requires_explicit_opt_in_before_reading_inputs(self):
        result = subprocess.run([sys.executable, str(HERE / 'prepare-proposal.py'),
            '--backend', '/missing/backend.apk', '--backend-report', '/missing/report.json',
            '--output', '/missing/output.apk'], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('explicit --enable-background-checkin is required', result.stderr)
        self.assertNotIn('Traceback', result.stderr)

    def test_job_service_is_android_bound_in_backend_process(self):
        root = ET.parse(HERE / 'manifest-fragment.xml').getroot()
        android = '{http://schemas.android.com/apk/res/android}'
        service, = root.findall('.//service')
        self.assertEqual(service.get(android + 'name'), 'inhouse.microg.bridge.CheckinJobService')
        self.assertEqual(service.get(android + 'permission'), 'android.permission.BIND_JOB_SERVICE')
        self.assertEqual(service.get(android + 'exported'), 'true')
        self.assertEqual(service.get(android + 'process'), ':inhouse_gms')
        self.assertIsNone(service.get(android + 'foregroundServiceType'))
        permissions = {element.get(android + 'name') for element in root.findall('uses-permission')}
        self.assertIn('android.permission.RECEIVE_BOOT_COMPLETED', permissions)
        self.assertNotIn('android.permission.SCHEDULE_EXACT_ALARM', permissions)
        self.assertNotIn('android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', permissions)


if __name__ == '__main__':
    unittest.main()
