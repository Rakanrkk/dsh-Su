import 'dart:async';
import 'dart:io';

import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/share_code/guest_fs.dart';
import 'package:dsh_app/state/permission_default_repair.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Bridge extends RuntimeBridge {
  _Bridge({this.content, this.written = false});
  final String? content;
  final bool written;
  int reads = 0;
  int writes = 0;

  @override
  Future<String?> readMainProfilePatch() async {
    reads++;
    return content;
  }

  @override
  Future<bool> writeMainProfilePatch(String content) async {
    writes++;
    expect(content, isNot(contains('defaultPreset')));
    return written;
  }
}

void main() {
  for (final (name, content, written, expectWrites) in [
    ('auto preset is cleared', '- id: permission\n  config:\n    defaultPreset: auto\n', true, 1),
    ('missing file does nothing', null, false, 0),
    ('no auto preset does nothing', '- id: permission\n  config:\n    defaultPreset: workspace-write\n', false, 0),
    ('failed write does not restart', '- id: permission\n  config:\n    defaultPreset: auto\n', false, 1),
  ]) {
    testWidgets(name, (tester) async {
      final bridge = _Bridge(content: content, written: written);
      final container = ProviderContainer.test(
        overrides: [
          runtimeBridgeProvider.overrideWithValue(bridge),
          localRuntimeProvider.overrideWith(_FakeRuntime.new),
        ],
      );
      addTearDown(container.dispose);
      await clearAutoDefaultPreset(container);
      expect(bridge.reads, 1);
      expect(bridge.writes, expectWrites);
    });
  }

  test('normal path waits for a delayed GuestFs', () async {
    final base = await Directory.systemTemp.createTemp('guest-fs');
    addTearDown(() => base.delete(recursive: true));
    final profileDir = Directory('${base.path}/root/.dsh/profiles/web');
    profileDir.createSync(recursive: true);
    final patch = File('${profileDir.path}/cordis.patch.yml');
    patch.writeAsStringSync('- id: permission\n  config:\n    defaultPreset: auto\n');
    final fs = GuestFs(base);
    final ready = Completer<GuestFs?>();
    final requestStarted = Completer<void>();
    final container = ProviderContainer.test(
      overrides: [
        guestFsProvider.overrideWith((ref) async {
          requestStarted.complete();
          return ready.future;
        }),
        localRuntimeProvider.overrideWith(_NormalRuntime.new),
      ],
    );
    addTearDown(container.dispose);
    final done = clearAutoDefaultPreset(container);
    await requestStarted.future;
    expect(patch.readAsStringSync(), contains('auto'));
    ready.complete(fs);
    await done;
    expect(patch.readAsStringSync(), isNot(contains('defaultPreset')));
  });
}

class _NormalRuntime extends LocalRuntimeController {
  @override
  LocalRuntimeState build() => const LocalRuntimeState(
    status: RuntimeStatus(phase: RuntimePhase.running, mode: EngineMode.safe),
  );
}

class _FakeRuntime extends LocalRuntimeController {
  @override
  LocalRuntimeState build() => const LocalRuntimeState(
    status: RuntimeStatus(phase: RuntimePhase.running, rootRuntime: true, mode: EngineMode.safe),
  );
}
