#!/usr/bin/env python3
import json
from pathlib import Path
import tempfile
import unittest
from prepare_pdf_sources import generate, MODULE, SOURCE, PREFIX


class NativePrintSourceTest(unittest.TestCase):
    def test_runtime_is_exactly_the_pinned_namespace_only_print_closure(self):
        manifest=json.loads((MODULE/'upstream.json').read_text())
        self.assertEqual(108,len(manifest['files']))
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)
            generate(output)
            actual=sorted(output.rglob('*.java'))
            self.assertEqual(100,len(actual))
            by_name={str(p.relative_to(output)):p for p in actual}
            for item in manifest['files']:
                if not item['runtime']:continue
                # MODULE = repo/compose/tux-compose-pdf.
                source=MODULE.parent.parent/item['path']
                relative=str(source.relative_to(SOURCE)).replace('app/tuxguitar',PREFIX.replace('.','/'),1)
                text=by_name[relative].read_text()
                self.assertEqual(source.read_text().replace('app.tuxguitar',PREFIX),text)
                self.assertNotIn('import android.',text)
                self.assertNotIn('import java.awt.',text)
                self.assertNotIn('.tuxguitar.io.',text)
            players=[p.name for p in actual if '/player/' in str(p)]
            self.assertEqual(['MidiInstrument.java','MidiPercussionKey.java'],sorted(players))

    def test_itext_is_test_only_and_core_never_depends_on_pdf(self):
        build=(MODULE/'build.gradle.kts').read_text()
        self.assertIn('testImplementation("com.itextpdf:itextpdf:',build)
        self.assertNotIn('implementation("com.itextpdf:',build)
        self.assertNotIn('project(":demo-tuxguitar")\n',build.replace('testImplementation(project(":demo-tuxguitar"))',''))
        core=(MODULE.parent/'tux-compose-core/build.gradle.kts').read_text()
        self.assertNotIn('tux-compose-pdf',core)
        self.assertNotIn('pdfbox',core)


if __name__=='__main__':
    unittest.main()
