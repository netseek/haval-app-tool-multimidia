"""Execute emitted wrappers in Node; never compile Android or a Frida bundle."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest

from cold_passthrough_controller import endpoint_launcher
from make_cold_launcher import build_launcher

ROOT = Path(__file__).resolve().parent
BRIDGE = '/data/local/tmp/impulse-carplay/versions/74138-123456789abc-abcdef123456/libcarplay_video_bridge.so'


@unittest.skipUnless(shutil.which('node'), 'Node is required to execute emitted wrappers')
class ResidencyWrappersTest(unittest.TestCase):
    def run_node(self, harness, source):
        subprocess.run(['node', '-e', harness], input=json.dumps(source), text=True,
                       check=True, capture_output=True)

    def test_native_init_and_dispose_preserve_existing_rpc_and_manual_autoarm(self):
        source = build_launcher(
            "function createColdOemInterceptor(api) {}\n"
            "rpc.exports={arm(){this.calls=(this.calls||0)+1;return{phase:'armed'}},"
            "status(){return{phase:'armed',pinned:true,replacements:0,failure:null}},cancel(){return'cancelled'}};",
            json.loads((ROOT / 'testdata/oem-cold-profile-v3.json').read_text()),
            42, BRIDGE, 4096, 'a' * 64, 'main')
        harness = r"""
const assert=require('node:assert/strict'),vm=require('node:vm');
const source=JSON.parse(require('node:fs').readFileSync(0,'utf8'));
const context={rpc:{exports:{}},send(){},setInterval(){return 1},clearInterval(){}};
vm.runInNewContext(source,context);
const rpc=context.rpc.exports;
assert.equal(rpc.calls,1);
assert.equal(rpc.init('early',{}).phase,'armed');
assert.equal(rpc.init('early',{}).phase,'armed');
assert.equal(rpc.calls,1);
assert.equal(rpc.cancel(),'cancelled');
rpc.dispose('unload');
assert.throws(()=>rpc.init('early',{impulseResidency:{}}),/disposed/);
assert.equal(rpc.status().pinned,true);
"""
        self.run_node(harness, source)

    def test_java_init_awaits_original_arm_promise_and_never_arms_twice(self):
        source = endpoint_launcher("// Persistent OEM-process control plane\n"
                                   "rpc.exports={arm(){if(pinCount!==1)throw Error('unprotected arm');armCount++;return armPromise},state(){return{armed:true}}};",
                                   bridge_path=BRIDGE)
        harness = r"""
const assert=require('node:assert/strict'),vm=require('node:vm');
const source=JSON.parse(require('node:fs').readFileSync(0,'utf8'));
let resolveArm;
const armPromise=new Promise(resolve=>{resolveArm=resolve});
const context={rpc:{exports:{}},armCount:0,pinCount:0,armPromise,send(){},File:class{write(){}flush(){}close(){}}};
context.Script={pin(){context.pinCount++},unpin(){throw Error('published callbacks must remain pinned')}};
vm.runInNewContext(source,context);
assert.equal(context.pinCount,1);
(async()=>{
 const rpc=context.rpc.exports;
 let completed=false;
 const init=rpc.init('early',{}).then(state=>{completed=true;return state});
 await Promise.resolve();await Promise.resolve();
 assert.equal(context.armCount,1);assert.equal(completed,false);
 resolveArm('armed');
 assert.equal(await init,'armed');
 assert.equal(await rpc.init('early',{}),'armed');
 assert.equal(context.armCount,1);assert.equal(rpc.state().armed,true);
 rpc.dispose('unload');assert.equal(context.pinCount,1);
})().catch(error=>{process.stderr.write(String(error.stack));process.exitCode=1});
"""
        self.run_node(harness, source)

    def test_java_init_finishing_after_dispose_cannot_start_a_resident_peer(self):
        source = endpoint_launcher("// Persistent OEM-process control plane\n"
                                   "rpc.exports={arm(){return armPromise},state(){return{armed:true}}};",
                                   bridge_path=BRIDGE)
        harness = r"""
const assert=require('node:assert/strict'),vm=require('node:vm');
const source=JSON.parse(require('node:fs').readFileSync(0,'utf8'));
let resolveArm;
const context={rpc:{exports:{}},armPromise:new Promise(resolve=>{resolveArm=resolve}),
 pinCount:0,send(){},File:class{write(){}flush(){}close(){}},setInterval(){throw Error('must not start a peer after disposal')}};
context.Script={pin(){context.pinCount++},unpin(){throw Error('published callbacks must remain pinned')}};
vm.runInNewContext(source,context);
(async()=>{
 const init=context.rpc.exports.init('early',{impulseResidency:{}});
 context.rpc.exports.dispose('unload');resolveArm('armed');
 await assert.rejects(init,/disposed/);
 await assert.rejects(context.rpc.exports.init('early',{impulseResidency:{}}),/disposed/);
 assert.equal(context.pinCount,1);
 let rejectArm;
 const failed={rpc:{exports:{}},pinCount:0,armPromise:new Promise((resolve,reject)=>{rejectArm=reject}),
  send(){},File:class{write(){}flush(){}close(){}}};
 failed.Script={pin(){failed.pinCount++},unpin(){throw Error('failed arm must not unpin published callbacks')}};
 vm.runInNewContext(source,failed);
 const rejected=failed.rpc.exports.init('early',{});
 rejectArm(Error('arm failed after callback publication'));
 await assert.rejects(rejected,/arm failed/);
 failed.rpc.exports.dispose('unload');assert.equal(failed.pinCount,1);
})().catch(error=>{process.stderr.write(String(error.stack));process.exitCode=1});
"""
        self.run_node(harness, source)


if __name__ == '__main__':
    unittest.main()
