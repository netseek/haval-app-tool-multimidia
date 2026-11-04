'use strict';

// Exercise the pinned dependency's real registerClass and DEX writer up to the
// Android load boundary. No Android/ART or native method execution is simulated.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, 'agent/node_modules/frida-java-bridge');
assert.equal(JSON.parse(fs.readFileSync(path.join(root, 'package.json'))).version, '7.0.13');
const factorySource = fs.readFileSync(path.join(root, 'lib/class-factory.js'), 'utf8');
const registerSource = factorySource.slice(factorySource.indexOf('  registerClass (spec) {'),
    factorySource.indexOf('\n  choose (specifier, callbacks) {'));
assert.ok(registerSource.startsWith('  registerClass (spec) {'));
const dexSource = fs.readFileSync(path.join(root, 'lib/mkdex.js'), 'utf8')
    .replace("import { Buffer } from 'buffer';", '')
    .replace('export default mkdex;', 'globalThis.mkdex = mkdex;');
const dexContext = {Buffer, Checksum: class {
    constructor(algorithm) { this.hash = crypto.createHash(algorithm); }
    update(bytes) { this.hash.update(bytes); }
    getDigest() { return this.hash.digest(); }
}};
vm.runInNewContext(dexSource, dexContext);
const descriptor = name => name === 'void' ? 'V' : 'L' + name.replaceAll('.', '/') + ';';
const type = name => ({name: descriptor(name), className: name});
const signatures = {
    onReceive: ['android.content.Context', 'android.content.Intent'],
    onServiceConnected: ['android.content.ComponentName', 'android.os.IBinder'],
    onServiceDisconnected: ['android.content.ComponentName'],
    onBindingDied: ['android.content.ComponentName'],
    onNullBinding: ['android.content.ComponentName']
};

function dexMethods(dex) {
    const strings = [];
    const count = dex.readUInt32LE(56), offset = dex.readUInt32LE(60);
    for (let index = 0; index < count; index++) {
        let start = dex.readUInt32LE(offset + index * 4);
        while ((dex[start++] & 0x80) !== 0) {} // ULEB128 UTF-16 length
        strings.push(dex.toString('utf8', start, dex.indexOf(0, start)));
    }
    const typeAt = index => strings[dex.readUInt32LE(dex.readUInt32LE(68) + index * 4)];
    const result = {};
    for (let index = 0; index < dex.readUInt32LE(88); index++) {
        const method = dex.readUInt32LE(92) + index * 8;
        const proto = dex.readUInt32LE(76) + dex.readUInt16LE(method + 2) * 12;
        const parameters = dex.readUInt32LE(proto + 8);
        const args = [];
        if (parameters !== 0) {
            for (let i = 0; i < dex.readUInt32LE(parameters); i++) {
                args.push(typeAt(dex.readUInt16LE(parameters + 4 + i * 2)));
            }
        }
        result[strings[dex.readUInt32LE(method + 4)]] = '(' + args.join('') + ')' + typeAt(dex.readUInt32LE(proto + 4));
    }
    return result;
}

function generatedMethods(definition) {
    const stop = new Error('DEX load boundary');
    let dex;
    const env = {javaLangReflectMethod() { return {}; }, vaMethod() { return () => []; },
        toReflectedMethod() { return {}; }, deleteLocalRef() {}};
    const context = {
        vm: {getEnv() { return env; }}, INSTANCE_METHOD: 1, STATIC_METHOD: 2, kAccStatic: 8,
        makeJniObjectTypeName: descriptor, makeSourceFileName: name => name + '.java',
        makeOverloadId: (name, ret, args) => name + '(' + args.map(t => t.name).join('') + ')' + ret.name,
        readTypeNames: () => [], mkdex: dexContext.mkdex,
        DexFile: {fromBuffer(bytes) { dex = bytes; throw stop; }}
    };
    const registerClass = vm.runInNewContext('({' + registerSource + '}).registerClass', context);
    const androidClass = name => {
        const value = {$n: name, class: {getInterfaces() { return []; }},
            $ownMembers: [], $borrowClassHandle() { return {value: {}, unref() {}}; }};
        if (name === 'android.content.ServiceConnection') {
            value.$ownMembers = Object.keys(signatures).filter(method => method !== 'onReceive');
            for (const method of value.$ownMembers) {
                value[method] = {overloads: [{type: 1, returnType: type('void'),
                    argumentTypes: signatures[method].map(type), handle: {}}]};
            }
        }
        return value;
    };
    const spec = {...definition,
        superClass: androidClass(definition.superClass ? 'android.content.BroadcastReceiver' : 'java.lang.Object'),
        implements: definition.implements ? [androidClass('android.content.ServiceConnection')] : []};
    assert.throws(() => registerClass.call({use: androidClass, _getType: type}, spec), error => error === stop);
    return dexMethods(dex);
}

function validateCallbacks(definition) {
    const actual = generatedMethods(definition);
    const expected = definition.superClass ? ['onReceive'] : Object.keys(signatures).filter(name => name !== 'onReceive');
    const methods = {};
    for (const name of expected) {
        const signature = '(' + signatures[name].map(descriptor).join('') + ')V';
        assert.equal(actual[name], signature, 'Android override descriptor for ' + name);
        // Require explicit overrides even where current interface inference works.
        assert.equal(definition.methods[name].returnType, 'void');
        assert.deepEqual(Array.from(definition.methods[name].argumentTypes), signatures[name]);
        assert.equal(typeof definition.methods[name].implementation, 'function');
        methods[name] = definition.methods[name].implementation;
    }
    return methods;
}

// Reproduce the old bug using the dependency itself, including a real DEX file:
// superclass methods are never included in registerClass's interface inference.
const broken = {name: 'test.BrokenReceiver', superClass: {}, methods: {onReceive(context, intent) {}}};
assert.equal(generatedMethods(broken).onReceive, '()V');
assert.throws(() => validateCallbacks(broken), /Android override descriptor for onReceive/);
module.exports = {validateCallbacks};
