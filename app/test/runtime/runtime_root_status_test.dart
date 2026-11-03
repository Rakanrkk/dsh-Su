// Root build flag on RuntimeStatus: getStatus and the status event share
// fromMap, missing fields stay a normal build, and the controller copy keeps
// the status it was given.

import 'dart:async';

import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Bridge extends RuntimeBridge {
  _Bridge(MethodChannel methods, EventChannel events)
    : super(methods: methods, events: events);

  final stream = StreamController<RuntimeEvent>.broadcast(sync: true);
  final initialStatus = Completer<RuntimeStatus>();

  @override
  Stream<RuntimeEvent> get events => stream.stream;
  @override
  Future<RuntimeStatus> status() => initialStatus.future;
  @override
  Future<RuntimeLogSnapshot> logSnapshot({int lines = 300}) async =>
      const RuntimeLogSnapshot([], 0);
}

void main() {
  test('fromMap parses the exact root flag and defaults to a normal build', () {
    const root = {'phase': 'installed', 'rootRuntime': true};
    expect(RuntimeStatus.fromMap(root).rootRuntime, isTrue);
    const normal = {'phase': 'installed', 'rootRuntime': false};
    expect(RuntimeStatus.fromMap(normal).rootRuntime, isFalse);
    const legacy = {'phase': 'installed'};
    expect(RuntimeStatus.fromMap(legacy).rootRuntime, isFalse);
    expect(const RuntimeStatus.unknown().rootRuntime, isFalse);
  });

  test('a truthy non-bool still does not claim Root', () {
    expect(
      RuntimeStatus.fromMap({'phase': 'installed', 'rootRuntime': 'true'})
          .rootRuntime,
      isFalse,
    );
    expect(
      RuntimeStatus.fromMap({'phase': 'installed', 'rootRuntime': 1})
          .rootRuntime,
      isFalse,
    );
  });

  test('getStatus and the status event both go through fromMap', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    const methods = MethodChannel('test/root-status');
    const events = EventChannel('test/root-status-events');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(methods, (call) async {
      expect(call.method, 'getStatus');
      return {'phase': 'installed', 'rootRuntime': true};
    });
    messenger.setMockStreamHandler(
      events,
      MockStreamHandler.inline(
        onListen: (arguments, sink) => sink.success({
          'type': 'status',
          'status': {'phase': 'installed', 'rootRuntime': true},
        }),
      ),
    );
    addTearDown(() {
      messenger.setMockMethodCallHandler(methods, null);
      messenger.setMockStreamHandler(events, null);
    });
    final bridge = RuntimeBridge(methods: methods, events: events);
    expect((await bridge.status()).rootRuntime, isTrue);
    expect(
      (await bridge.events.first as RuntimeStatusEvent).status.rootRuntime,
      isTrue,
    );
  });

  test('the controller keeps the Root status through copyWith', () async {
    final bridge = _Bridge(const MethodChannel('unused'), const EventChannel('unused'));
    final container = ProviderContainer(
      overrides: [runtimeBridgeProvider.overrideWithValue(bridge)],
    );
    addTearDown(container.dispose);
    container.read(localRuntimeProvider);
    bridge.initialStatus.complete(
      RuntimeStatus.fromMap({'phase': 'installed', 'rootRuntime': true}),
    );
    await Future<void>.delayed(Duration.zero);
    expect(container.read(localRuntimeProvider).status.rootRuntime, isTrue);
    expect(container.read(localRuntimeProvider).loaded, isTrue);
    bridge.stream.add(const RuntimeProgressEvent(step: 'extract', done: 1, total: 2));
    expect(container.read(localRuntimeProvider).status.rootRuntime, isTrue);
  });
}
