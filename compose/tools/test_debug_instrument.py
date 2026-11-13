#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import debug_instrument as runner


def event(test,code,stack='',count=2):
    return ('INSTRUMENTATION_STATUS: class=com.tuxcompose.sample.Example\n'
            f'INSTRUMENTATION_STATUS: test={test}\n'
            f'INSTRUMENTATION_STATUS: numtests={count}\n'+
            (f'INSTRUMENTATION_STATUS: stack={stack}\n' if stack else '')+
            f'INSTRUMENTATION_STATUS_CODE: {code}\n')


def suite(second=0):
    return event('one',1)+event('one',0)+event('two',1)+event('two',second,'failure line\nat example.Code' if second else '')+'INSTRUMENTATION_CODE: -1\n'


class NonStreamingRunnerTest(unittest.TestCase):
    def test_complete_suite_is_not_filtered(self):
        cases=runner.parse(suite())
        self.assertEqual(2,len(cases))
        self.assertTrue(all(v['code']==0 for v in cases.values()))
        self.assertEqual('0',runner.xml_results(cases).get('failures'))

    def test_incomplete_suite_is_an_error(self):
        output=event('one',1)+event('one',0)+'INSTRUMENTATION_CODE: -1\n'
        with self.assertRaisesRegex(AssertionError,'Incomplete runner'):
            runner.parse(output)

    def test_crashed_process_cannot_be_reported_as_success(self):
        with self.assertRaisesRegex(AssertionError,'finish normally'):
            runner.parse(suite().replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_CODE: 0'))

    def test_failure_and_multiline_stack_survive_as_junit(self):
        cases=runner.parse(suite(-2));report=runner.xml_results(cases)
        self.assertEqual('1',report.get('failures'))
        self.assertIn('at example.Code',report.find('./testcase/failure').text)
        self.assertFalse(all(v['code']==0 for v in cases.values()))

    def test_skips_remain_skips_not_passes(self):
        cases=runner.parse(suite(-3))
        self.assertEqual('1',runner.xml_results(cases).get('skipped'))
        self.assertFalse(all(v['code']==0 for v in cases.values()))

    def test_duplicate_case_finish_is_not_accepted(self):
        with self.assertRaisesRegex(AssertionError,'Duplicate finish'):
            runner.parse(suite().replace('INSTRUMENTATION_CODE: -1',event('one',0)+'INSTRUMENTATION_CODE: -1'))

    def test_install_uses_one_bounded_non_streaming_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            apk=Path(directory)/'test.apk';apk.write_bytes(b'input APK')
            with patch.object(runner,'adb',return_value=b'Success\n') as adb:
                result=runner.install(apk)
                self.assertEqual('non-streaming',result['transport'])
                self.assertEqual(('install','--no-streaming','-r',str(apk)),adb.call_args.args)
                self.assertEqual({'timeout':180},adb.call_args.kwargs)
                self.assertEqual(1,adb.call_count)
            with patch.object(runner,'adb',return_value=b'Failure [INSTALL_FAILED]') as adb:
                with self.assertRaises(AssertionError):runner.install(apk)
                self.assertEqual(1,adb.call_count)


if __name__=='__main__':unittest.main()
