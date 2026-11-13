#!/usr/bin/env python3
"""Host-side harness regressions; actual rendering is tested on API 24 and 35."""
import contextlib
import hashlib
import io
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

import release_smoke as smoke


class ReleaseCaptureTest(unittest.TestCase):
    def test_checked_predicate_waits_for_committed_selection(self):
        docs = [ET.fromstring(f'<hierarchy><node resource-id="mode-Mixed" checked="{value}" /></hierarchy>')
                for value in ('false', 'true')]
        with patch.object(smoke, 'tree', side_effect=docs) as tree, patch.object(smoke.time, 'sleep'):
            node = smoke.wait_node(tag='mode-Mixed', predicate=lambda n: n.get('checked') == 'true')
        self.assertEqual(node.get('checked'), 'true')
        self.assertEqual(tree.call_count, 2)

    def test_workspace_waits_until_dialog_really_disappears(self):
        docs = [ET.fromstring('<hierarchy><node resource-id="score-canvas"/><node resource-id="cursor-tick"/></hierarchy>'),
                ET.fromstring('<hierarchy><node resource-id="score-canvas"/></hierarchy>')]
        with patch.object(smoke, 'tree', side_effect=docs) as tree, patch.object(smoke.time, 'sleep'):
            smoke.wait_workspace()
        self.assertEqual(tree.call_count, 2)

    def test_overlay_wait_tolerates_transient_xml_during_recreation(self):
        with patch.object(smoke, 'tree', side_effect=[ET.ParseError('in-flight frame'), ET.fromstring('<hierarchy/>')]) as tree, patch.object(smoke.time, 'sleep'):
            smoke.wait_gone(tag='cursor-tick')
        self.assertEqual(tree.call_count, 2)

    def test_native_picker_edittext_does_not_match_a_toolbar_title(self):
        doc=ET.fromstring('<hierarchy><node resource-id="android:id/title" class="android.widget.TextView"/><node resource-id="android:id/title" class="android.widget.EditText"/></hierarchy>')
        node=smoke.find(doc,tag='title',class_name='android.widget.EditText')
        self.assertEqual('android.widget.EditText',node.get('class'))

    def capture(self, frames, *, settled=True, clock=None):
        # Minimal byte tokens exercise frame identity/control flow, not PNG decoding.
        with tempfile.TemporaryDirectory() as folder, patch.object(smoke, 'out', Path(folder)), patch.object(smoke, 'captures', {}), patch.object(smoke, 'wait_workspace') as workspace, patch.object(smoke, 'adb', side_effect=frames) as adb, patch.object(smoke.time, 'sleep'):
            if clock is None:
                smoke.screenshot('test-scene', settled=settled)
            else:
                with patch.object(smoke.time, 'monotonic', side_effect=clock):
                    smoke.screenshot('test-scene', settled=settled)
            return (Path(folder, 'test-scene.png').read_bytes(), smoke.captures['test-scene'], adb.call_count, workspace.call_count)

    def test_screenshot_requires_two_identical_actual_frames(self):
        a, b = (b'\x89PNG\r\n\x1a\n'+v for v in (b'old', b'new'))
        data, metadata, attempts, waits = self.capture([a, b, b])
        self.assertEqual(data, b)
        self.assertEqual(metadata['sha256'], hashlib.sha256(b).hexdigest())
        self.assertEqual((attempts, waits, metadata['settled']), (3, 1, True))

    def test_failure_evidence_is_not_falsely_labelled_settled(self):
        data = b'\x89PNG\r\n\x1a\nevidence'
        _, metadata, attempts, waits = self.capture([data], settled=False)
        self.assertEqual((attempts, waits, metadata['settled']), (1, 0, False))

    def test_invalid_capture_is_a_failure(self):
        with self.assertRaisesRegex(AssertionError, 'Invalid display screenshot'):
            self.capture([b'adb failed'])

    def test_unstable_display_is_a_failure_not_an_approved_scene(self):
        a, b = (b'\x89PNG\r\n\x1a\n'+v for v in (b'old', b'new'))
        with self.assertRaisesRegex(AssertionError, 'Display did not settle'):
            self.capture([a, b], clock=[0, 0, 21])


class ReleaseInstallTest(unittest.TestCase):
    def attempt(self,output=b'Success\n',info=b'versionCode=10 minSdk=24\nversionName=1.0.0\n'):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'release.apk';path.write_bytes(b'verified APK')
            proof=dict(apk_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),version='1.0.0',version_code=10)
            with patch.object(smoke,'report',Path(folder)),patch.object(smoke,'adb',side_effect=[b'',b'',output,info]) as adb,contextlib.redirect_stdout(io.StringIO()) as log:
                smoke.install_verified_release(path,proof)
                result=(Path(folder)/'release-installation.json').read_text()
                return adb.call_args_list,result,log.getvalue()

    def test_install_gets_a_distinct_bounded_budget_and_real_success_proof(self):
        calls,result,log=self.attempt()
        self.assertEqual(calls[2].kwargs,dict(timeout=180))
        self.assertIn('"installed": true',result)
        self.assertIn('Exact release APK installed',log)
        self.assertEqual(len(calls),4)

    def test_missing_installer_success_cannot_be_mislabelled_installed(self):
        with self.assertRaisesRegex(AssertionError,'Installer did not confirm success'):
            self.attempt(output=b'Failure [INSTALL_FAILED_TEST_ONLY]')

    def test_wrong_installed_version_is_not_accepted(self):
        with self.assertRaisesRegex(AssertionError,'version name differs'):
            self.attempt(info=b'versionCode=9\nversionName=0.1.8\n')

    def test_install_timeout_is_not_retried_or_swallowed(self):
        with patch.object(smoke.subprocess,'run',side_effect=subprocess.TimeoutExpired('adb',180)) as run:
            with self.assertRaises(subprocess.TimeoutExpired):smoke.adb('install','release.apk',timeout=180)
            self.assertEqual(run.call_count,1)
            self.assertEqual(run.call_args.kwargs['timeout'],180)


if __name__ == '__main__':
    unittest.main()
