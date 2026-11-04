import 'dart:async';

import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/engine_control.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Bridge extends RuntimeBridge {
  final stream = StreamController<RuntimeEvent>.broadcast(sync: true);
  final initialStatus = Completer<RuntimeStatus>();
  final initialLogs = Completer<RuntimeLogSnapshot>();
  final stopResult = Completer<void>();
  final stopOwnedResult = Completer<void>();
  final ownedStopTickets = <int>[];
  var starts = 0;
  var deferReady = false;
  var nextTicket = 1;

  @override
  Stream<RuntimeEvent> get events => stream.stream;
  @override
  Future<RuntimeStatus> status() => initialStatus.future;
  @override
  Future<RuntimeLogSnapshot> logSnapshot({int lines = 300}) => initialLogs.future;
  @override
  Future<void> stop() => stopResult.future;
  @override
  Future<void> stopOwnedStart(int ticket) async {
    ownedStopTickets.add(ticket);
    await stopOwnedResult.future;
    stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.installed)));
  }

  @override
  Future<void> start({
    Map<String, String> env = const {},
    bool phoneStorage = false,
    bool visualReplies = true,
    EngineMode? mode,
  }) async {
    await startOwned(env: env, phoneStorage: phoneStorage, visualReplies: visualReplies, mode: mode);
  }

  @override
  Future<int> startOwned({
    Map<String, String> env = const {},
    bool phoneStorage = false,
    bool visualReplies = true,
    EngineMode? mode,
  }) async {
    starts++;
    final ticket = nextTicket++;
    stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.starting)));
    if (!deferReady) stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.running)));
    return ticket;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _Bridge bridge;
  late ProviderContainer container;
  setUp(() {
    bridge = _Bridge();
    container = ProviderContainer(overrides: [runtimeBridgeProvider.overrideWithValue(bridge)]);
    container.read(localRuntimeProvider);
  });
  tearDown(() async {
    container.dispose();
    await bridge.stream.close();
  });
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<void> flush() => Future<void>.delayed(Duration.zero);

  test('ordinary start omits owner ticket and restart start returns one', () async {
    final calls = <MethodCall>[];
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    const channel = MethodChannel('dsh/runtime');
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return call.method == 'start' && (call.arguments as Map)['returnTicket'] == true ? 73 : null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    final bridge = RuntimeBridge();
    await bridge.start();
    final ticket = await bridge.startOwned();
    await bridge.stopOwnedStart(ticket);

    expect(ticket, 73);
    expect(calls.map((call) => call.method), ['start', 'start', 'stop']);
    expect((calls[0].arguments as Map).containsKey('returnTicket'), isFalse);
    expect((calls[1].arguments as Map)['returnTicket'], isTrue);
    expect((calls[2].arguments as Map)['startTicket'], 73);
  });

  test('late logs cannot overwrite a newer running event', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.starting));
    await flush();
    expect(container.read(localRuntimeProvider).loaded, isTrue);
    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.running)));
    bridge.initialLogs.complete(const RuntimeLogSnapshot([RuntimeLogEntry(1, 'old log', 'stdout')], 1));
    await flush();
    expect(container.read(localRuntimeProvider).status.phase, RuntimePhase.running);
    expect(container.read(localRuntimeLogsProvider), ['old log']);
  });

  test('keeps the most recent 1000 log lines', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.installed));
    bridge.initialLogs.complete(const RuntimeLogSnapshot([], 0));
    await flush();
    for (var i = 0; i < 1005; i++) {
      bridge.stream.add(RuntimeLogsEvent([RuntimeLogEntry(i + 1, 'line $i', 'stdout')]));
    }
    final logs = container.read(localRuntimeLogsProvider);
    expect(logs.length, 1000);
    expect(logs.first, 'line 5');
    expect(logs.last, 'line 1004');
  });

  test('leaving installation clears progress and notices advance sequence', () {
    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.installing)));
    bridge.stream.add(const RuntimeProgressEvent(step: 'extract', done: 1, total: 2));
    expect(container.read(localRuntimeProvider).progress?.done, 1);
    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.running)));
    expect(container.read(localRuntimeProvider).progress, isNull);
    bridge.stream.add(const RuntimeNoticeEvent('已启动'));
    expect(container.read(localRuntimeProvider).notice?.seq, 1);
    bridge.stream.add(const RuntimeNoticeEvent('已启动'));
    expect(container.read(localRuntimeProvider).notice?.seq, 2);
  });

  test('a status failure still ends initial loading', () async {
    bridge.initialStatus.completeError(StateError('offline'));
    await flush();
    expect(container.read(localRuntimeProvider).loaded, isTrue);
  });

  test('restart does not start until stop completion is observed', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.running));
    await flush();

    final restarting = EngineControl.restart(container, stopTimeout: const Duration(seconds: 1));
    await flush();
    expect(bridge.starts, 0);

    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.installed)));
    bridge.stopResult.complete();
    await restarting;
    expect(bridge.starts, 1);
  });

  test('restart timeout fails without starting', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.running));
    await flush();

    await expectLater(
      EngineControl.restart(container, stopTimeout: const Duration(milliseconds: 10)),
      throwsA(isA<TimeoutException>()),
    );
    expect(bridge.starts, 0);
  });

  test('an unconfirmed stop failure prevents the next start', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.running));
    await flush();
    bridge.stopResult.completeError(StateError('runtime stop or root cleanup is unconfirmed'));

    await expectLater(
      EngineControl.restart(container, stopTimeout: const Duration(seconds: 1)),
      throwsA(isA<StateError>().having((error) => error.message, 'message', contains('root cleanup is unconfirmed'))),
    );
    expect(bridge.starts, 0);
  });

  test('restart completes only after the new run is ready', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.running));
    bridge.deferReady = true;
    await flush();

    final restarting = EngineControl.restart(container, stopTimeout: const Duration(seconds: 1));
    var completed = false;
    unawaited(restarting.then((_) => completed = true));
    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.installed)));
    bridge.stopResult.complete();
    await flush();
    expect(bridge.starts, 1);
    expect(completed, isFalse);

    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.running)));
    await restarting;
  });

  test('restart reports a new run failure', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.running));
    bridge.deferReady = true;
    await flush();

    final restarting = EngineControl.restart(container, stopTimeout: const Duration(seconds: 1));
    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.installed)));
    bridge.stopResult.complete();
    await flush();
    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.starting)));
    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.error, error: 'root startup failed')));
    await expectLater(
      restarting,
      throwsA(isA<StateError>().having((error) => error.message, 'message', contains('root startup failed'))),
    );
  });

  test('cancel after start acceptance stops that owner before a later start', () async {
    bridge.initialStatus.complete(const RuntimeStatus(phase: RuntimePhase.installed));
    bridge.deferReady = true;
    await flush();

    final cancellation = Completer<void>();
    final restarting = EngineControl.restart(container, cancelRequested: cancellation.future, isCurrent: () => true);
    await flush();
    expect(bridge.starts, 1);

    var completed = false;
    unawaited(
      restarting.then<void>(
        (_) {
          completed = true;
        },
        onError: (Object error, StackTrace stack) {
          completed = true;
        },
      ),
    );
    cancellation.complete();
    await flush();
    expect(bridge.ownedStopTickets, [1]);
    expect(completed, isFalse);

    bridge.stream.add(const RuntimeStatusEvent(RuntimeStatus(phase: RuntimePhase.running)));
    await flush();
    expect(completed, isFalse);

    bridge.stopOwnedResult.complete();
    await expectLater(
      restarting,
      throwsA(isA<StateError>().having((error) => error.message, 'message', contains('cancelled'))),
    );

    bridge.deferReady = false;
    await EngineControl.restart(container, isCurrent: () => true);
    expect(bridge.starts, 2);
    expect(bridge.ownedStopTickets, [1]);
  });
}
