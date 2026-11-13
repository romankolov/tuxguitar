#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from verify_device_captures import required_scenes, verify


class HardwareCaptureExportTest(unittest.TestCase):
    def test_all_41_required_scenes_are_explicit(self):
        self.assertEqual(41,len(required_scenes()))
        self.assertIn('pdf-Mixed-first',required_scenes())
        self.assertIn('pdf-result',required_scenes())
        self.assertIn('annotation-origins-Mixed-1.0',required_scenes())
        self.assertIn('engraving-Mixed-TuxGuitar-1.0',required_scenes())

    def test_manifest_hashes_original_png_and_rejects_missing_scene(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            Image.new('RGB',(12,8),'white').save(folder/'hardware.png')
            report=verify(folder,{'hardware'})
            self.assertEqual([12,8],report['images']['hardware']['size'])
            self.assertEqual(64,len(report['images']['hardware']['sha256']))
            with self.assertRaisesRegex(AssertionError,'lost before export'):
                verify(folder,{'hardware','missing'})

    def test_invalid_png_is_not_accepted_as_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'bad.png').write_text('not a screenshot')
            with self.assertRaises(Exception):
                verify(folder,{'bad'})

    def test_duplicate_names_are_not_silently_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);(folder/'nested').mkdir()
            for parent in (folder,folder/'nested'):
                Image.new('RGB',(12,8)).save(parent/'hardware.png')
            with self.assertRaisesRegex(AssertionError,'Duplicate'):
                verify(folder,{'hardware'})


if __name__=='__main__':
    unittest.main()
