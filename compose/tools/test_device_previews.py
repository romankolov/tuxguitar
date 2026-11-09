#!/usr/bin/env python3
import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from PIL import Image
from publish_device_previews import annotations, gh
from unittest.mock import patch
import subprocess


class DetailedPreviewExportTest(unittest.TestCase):
    def test_chunks_reassemble_and_preserve_original_png_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            Image.new('RGB',(945,945),'white').save(folder/'hardware.png')
            manifest,items=annotations(folder,{'hardware'})
            chunks=[json.loads(item['message']) for item in items]
            self.assertTrue(all(len(item['message'])<4000 for item in items))
            data=base64.b64decode(''.join(c['base64'] for c in chunks))
            self.assertEqual(chunks[0]['sha256'],hashlib.sha256(data).hexdigest())
            original=hashlib.sha256((folder/'hardware.png').read_bytes()).hexdigest()
            self.assertEqual(original,chunks[0]['original_png_sha256'])
            self.assertEqual([945,945],chunks[0]['original_png_size'])
            self.assertEqual(1,manifest['required'])
            self.assertTrue(all(item['annotation_level']=='notice' for item in items))

    def test_github_failure_preserves_http_diagnosis(self):
        failure=subprocess.CompletedProcess([],1,b'',b'HTTP 502: Bad Gateway')
        with patch('publish_device_previews.subprocess.run',return_value=failure):
            with self.assertRaisesRegex(RuntimeError,'GitHub PATCH.*HTTP 502'):
                gh('repos/example/example/check-runs/1','PATCH',{})

    def test_github_invalid_json_is_not_silent(self):
        failure=subprocess.CompletedProcess([],0,b'not-json',b'')
        with patch('publish_device_previews.subprocess.run',return_value=failure):
            with self.assertRaisesRegex(RuntimeError,'returned invalid JSON: not-json'):
                gh('repos/example/example/check-runs','POST',{})

    def test_export_cannot_silently_skip_missing_required_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(AssertionError,'lost before export'):
                annotations(Path(directory),{'required-frame'})


if __name__=='__main__':
    unittest.main()
