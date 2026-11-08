#!/usr/bin/env python3
"""Host-side harness regressions; actual rendering is tested on API 24 and 35."""
import hashlib
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


if __name__ == '__main__':
    unittest.main()
