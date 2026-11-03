"""Execute the emitted JavaScript; Python/JSON representations hide slash counts."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest

from cold_passthrough_controller import ENDPOINT_STATUS_PROCESS, endpoint_agent_entry

ROOT = Path(__file__).resolve().parent
BOOT = '12345678-abcd-4abc-8def-123456789abc'
PID = 20459


def stat(pid=PID, ticks='4869268', state='S'):
    # Field 22 is starttime; comm can itself contain spaces and parentheses.
    return f'{pid} (com.ts.carplay (worker)) ' + '\t  '.join([state] + ['0'] * 18 + [ticks] + ['0'] * 20) + '\n'


@unittest.skipUnless(shutil.which('node'), 'Node required for actual emitted endpoint execution')
class EndpointRuntimePathTest(unittest.TestCase):
    def prefix(self):
        entry = endpoint_agent_entry((ROOT / 'carplay_clean_map_endpoint.js').read_text(),
                                     status_mode=ENDPOINT_STATUS_PROCESS)
        self.assertTrue(entry.startswith('import Java from "frida-java-bridge";\n'))
        self.assertNotIn('Object.defineProperty(exports', entry)
        return entry[entry.index('const impulseEndpointStatusPath'):entry.index('function impulsePublishEndpoint')]

    def execute(self, source, cases):
        harness = r"""
const vm = require('node:vm'), fs = require('node:fs');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const results = input.cases.map(item => {
    const reads = [];
    let closed = 0;
    class File {
        constructor(path, mode) {
            if (mode !== 'r') throw Error('Unexpected write');
            this.path = path;
        }
        readText(max) {
            reads.push({path:this.path, max});
            // procfs stat_size is zero: implicit Frida readText() returns empty.
            if (max === undefined) return '';
            const boot = this.path === '/proc/sys/kernel/random/boot_id';
            if (!boot && this.path !== '/proc/self/stat') throw Error('Unexpected identity path');
            if (max !== (boot ? 129 : 4097)) throw Error('Unexpected identity read bound');
            return (boot ? item.boot : item.stat).slice(0, max);
        }
        close() { closed++; if (item.closeThrows) throw Error('close failed'); }
    }
    const context = {File, Process:{id:input.pid}};
    try {
        vm.runInNewContext(input.source + ';this.answer=impulseEndpointStatusPath;', context);
        return {path:context.answer, reads, closed};
    } catch (error) { return {error:String(error), reads, closed}; }
});
process.stdout.write(JSON.stringify(results));
"""
        result = subprocess.run(['node', '-e', harness], check=True, capture_output=True, text=True,
                                input=json.dumps({'source': source, 'cases': cases, 'pid': PID}))
        return json.loads(result.stdout)

    def test_single_slash_generated_regex_handles_kernel_whitespace(self):
        result = self.execute(self.prefix(), [{'boot': BOOT + '\n', 'stat': stat()}])[0]
        self.assertEqual(f'/data/local/tmp/impulse-carplay/runtime/{BOOT}-{PID}-4869268/endpoint-state.log', result['path'])

    def test_procfs_implicit_read_mutation_reproduces_live_empty_input(self):
        prefix = self.prefix()
        mutated = prefix.replace('file.readText(max + 1)', 'file.readText()')
        self.assertNotEqual(prefix, mutated)
        result = self.execute(mutated, [{'boot': BOOT + '\n', 'stat': stat()}])[0]
        self.assertIn('Invalid kernel boot identity', result['error'])
        self.assertEqual(1, result['closed'])

    def test_explicit_read_bounds_close_files_and_reject_oversized_inputs(self):
        cases = [
            {'boot': BOOT + '\n', 'stat': stat()},
            {'boot': BOOT + 'x' * 200, 'stat': stat()},
            {'boot': BOOT, 'stat': stat() + ' ' * 4097},
            {'boot': BOOT, 'stat': stat(), 'closeThrows': True},
        ]
        results = self.execute(self.prefix(), cases)
        self.assertEqual([129, 4097], [read['max'] for read in results[0]['reads']])
        self.assertEqual(2, results[0]['closed'])
        self.assertIn('exceeds bound', results[1]['error'])
        self.assertIn('exceeds bound', results[2]['error'])
        self.assertEqual([1, 2], [results[1]['closed'], results[2]['closed']])
        self.assertEqual(results[0]['path'], results[3]['path'])

    def test_double_slash_mutation_reproduces_failure_before_loaded_event(self):
        prefix = self.prefix()
        mutated = prefix.replace(r'split(/\s+/)', r'split(/\\s+/)')
        self.assertNotEqual(prefix, mutated)
        result = self.execute(mutated, [{'boot': BOOT + '\n', 'stat': stat()}])[0]
        self.assertIn('Invalid OEM process start identity', result['error'])

    def test_reused_pid_gets_distinct_path_and_untrusted_identity_fails_closed(self):
        cases = [
            {'boot': BOOT, 'stat': stat()}, {'boot': BOOT, 'stat': stat(ticks='4869999')},
            {'boot': BOOT, 'stat': stat(pid=PID + 1)}, {'boot': BOOT, 'stat': stat(state='Z')},
            {'boot': BOOT, 'stat': stat(state='X')}, {'boot': BOOT, 'stat': stat(ticks='0')},
            {'boot': BOOT, 'stat': 'truncated'}, {'boot': 'untrusted', 'stat': stat()},
        ]
        results = self.execute(self.prefix(), cases)
        self.assertNotEqual(results[0]['path'], results[1]['path'])
        self.assertTrue(results[1]['path'].endswith(f'-{PID}-4869999/endpoint-state.log'))
        self.assertTrue(all('error' in item and 'path' not in item for item in results[2:]))


if __name__ == '__main__':
    unittest.main()
