#!/usr/bin/env python3
import hashlib
from pathlib import Path
import tempfile
import unittest
from pypdf import PdfWriter
from pypdf.generic import DictionaryObject, NameObject, DecodedStreamObject
from verify_pdf_exports import verify


class ExportedPdfVerifierTest(unittest.TestCase):
    def fixture(self,folder,image=False):
        writer=PdfWriter();page=writer.add_blank_page(width=550,height=800)
        font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Times-Roman')})
        resources=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):writer._add_object(font)})})
        if image:
            resources[NameObject('/XObject')]=DictionaryObject({NameObject('/Im1'):DictionaryObject({NameObject('/Subtype'):NameObject('/Image')})})
        page[NameObject('/Resources')]=resources
        stream=DecodedStreamObject();stream.set_data(b'BT /F1 10 Tf 20 700 Td (Technique Atlas) Tj 0 -20 Td (Track Acoustic guitar) Tj 0 -20 Td (Track Fingered bass) Tj 0 -20 Td (Track Studio drums) Tj 0 -600 Td (1) Tj ET')
        page[NameObject('/Contents')]=writer._add_object(stream)
        path=folder/'pdf-Mixed.pdf'
        with path.open('wb') as file:writer.write(file)
        return path

    def test_missing_real_pdf_cannot_be_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(AssertionError,'Generated PDF lost'):
                verify(Path(directory),{'missing.pdf'})

    def test_actual_pdf_structure_fonts_and_hash_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);path=self.fixture(folder)
            record=verify(folder,{'pdf-Mixed.pdf'})['pdf-Mixed.pdf']
            self.assertEqual(1,record['pages'])
            self.assertEqual([[550.0,800.0]],record['sizes'])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),record['sha256'])

    def test_raster_score_resource_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);self.fixture(folder,image=True)
            with self.assertRaisesRegex(AssertionError,'Raster/form score'):
                verify(folder,{'pdf-Mixed.pdf'})


if __name__=='__main__':
    unittest.main()
