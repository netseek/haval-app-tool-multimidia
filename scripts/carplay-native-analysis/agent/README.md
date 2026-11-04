# CarPlay Java control agent

This is a build-time dependency tree for the separately staged experimental
endpoint. It does not change the APK, start CarPlay, install native hooks, or
contact the head unit while building.

Custom GumJS agents require an explicit Java bridge starting with Frida 17;
the REPL's bundled Java support must not be assumed for `fridainjector`.
See the [official bridge instructions](https://frida.re/docs/bridges/).
The current injector's actual Java global remains unobserved because the final
read-only probe could not reach the offline car. The bundled agent removes that
dependency on injector-specific globals.

From the repository root:

```sh
npm ci --prefix scripts/carplay-native-analysis/agent --ignore-scripts --no-audit --no-fund
python3 scripts/carplay-native-analysis/build_endpoint_agent.py
```

This runs only a JavaScript bundler. Every Android/Gradle/Kotlin build still goes
through the authorized Mac Mini queue. Pinned package versions and integrity
hashes are in `package-lock.json`. The builder checks installed versions, omits
source maps, and refuses to overwrite an existing bundle. Select another
`--output` path when preserving a previous result.

The result is a complete Frida module archive and a `.manifest.json` sidecar.
The package and isolated compiler directory explicitly declare `type: module`.
Without it, the compiler's Node16 mode emits an entry that references CommonJS
`exports`/`require`; Gum does not provide these globals and the endpoint never
starts. The builder and controller now reject that entry format and pin the
package metadata and ESM mode in the receipt. A durable `loaded` event separates
successful module evaluation from the later Java `armed` acknowledgement.
The controller verifies the exact bytes, generated entry, dependency lock,
versions, and remote native-library path before staging. It uploads the complete
archive unchanged; prepending a plaintext launcher would invalidate its format.
These local hashes detect stale/altered artifacts, not an untrusted producer.

The controller defaults to `agent/dist/clean-map-agent.js`. An operator can select
an existing verified artifact with `--endpoint-bundle /path/agent.js`. The raw
`--endpoint` option is retained only for an environment already supplying Java.

The endpoint owns a binding attempt independently of JavaScript wrapper identity.
Stop/failure closes pending endpoints and clears tokens before unbinding; old
callbacks cannot retire a newer attempt. Generation is rechecked before the offer.
Portable models cover these cases; they are not real Android/Binder/Surface tests.

The package metadata identifies `frida-java-bridge` as
`LGPL-2.0 WITH WxWindows-exception-3.1`. Preserve its upstream licensing when
redistributing the compiled agent. No agent artifact or dependency cache is tracked.
