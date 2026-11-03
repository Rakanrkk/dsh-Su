// Root delivery through ChatAlertsController: a denied post stays available
// for SystemAccessDriver's retry, foreground banners are kept, the old owner's
// read / cancel / removal never comes back late, and the normal path keeps
// the legacy behavior.

import 'dart:async';

import 'package:dsh_app/phone/phone_channel.dart';
import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/state/hosts_state.dart';
import 'package:dsh_app/state/notify_state.dart';
import 'package:dsh_app/state/phone_state.dart' show phoneChannelProvider;
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support.dart';

class _Local extends LocalConnectionController {
  _Local(this.connection);

  final HostConnection? connection;

  @override
  Future<HostConnection?> build() async => connection;
}

class _NoPhone extends PhoneChannel {
  @override
  Future<PhoneStatus> status() async => const PhoneStatus.notReady();
}

class _Mux extends DshMux {
  _Mux(super.carrier);
  final current = StreamController<DshConnectionState>.broadcast();
  @override
  MuxGeneration? get generation => const MuxGeneration(1, 'remote-test', '/tmp');
  @override
  Stream<DshConnectionState> get states => current.stream;
}

class _Client extends DshClient {
  _Client() : super(DshConnection(Uri.parse('http://127.0.0.1:9')));
  late final testMux = _Mux(carrier);
  @override
  DshMux get mux => testMux;
  @override
  Future<void> respondApproval(ApprovalRequest request, ApprovalDecision decision) async {}
}

class _Runtime extends LocalRuntimeController {
  @override
  LocalRuntimeState build() => LocalRuntimeState(
    loaded: true,
    status: RuntimeStatus(phase: RuntimePhase.installed, rootRuntime: true),
  );
}

class _CurrentConnection extends Notifier<AsyncValue<HostConnection?>> {
  _CurrentConnection(this.initial);

  final AsyncValue<HostConnection?> initial;

  @override
  AsyncValue<HostConnection?> build() => initial;

  void set(AsyncValue<HostConnection?> connection) => state = connection;
}

final _currentConnectionProvider =
    NotifierProvider<_CurrentConnection, AsyncValue<HostConnection?>>(
      () => _CurrentConnection(const AsyncData(null)),
    );

void main() {
  late FakeNotifyChannel channel;
  late FakeAlertSource remote;
  late ProviderContainer container;

  ProviderContainer make({required bool foreground, bool runningSession = false}) {
    SharedPreferences.setMockInitialValues({});
    channel = FakeNotifyChannel();
    remote = FakeAlertSource()
      ..sessions = [session('r1', title: '服务器任务', running: runningSession)];
    final connection = HostConnection(
      client: _Client(),
      label: '远程',
      isLocal: false,
      kind: HostKind.ssh,
      hostId: 'lab',
    );
    final c = ProviderContainer(
      overrides: [
        notifyChannelProvider.overrideWithValue(channel),
        alertSourceFactoryProvider.overrideWithValue((_) => remote),
        alertTimingProvider.overrideWithValue(
          (announce: Duration.zero, reconcile: const Duration(milliseconds: 30)),
        ),
        localConnectionProvider.overrideWith(() => _Local(null)),
        activeConnectionProvider.overrideWith((ref) => ref.watch(_currentConnectionProvider)),
        _currentConnectionProvider.overrideWith(
          () => _CurrentConnection(AsyncData(connection)),
        ),
        phoneChannelProvider.overrideWithValue(_NoPhone()),
        localRuntimeProvider.overrideWith(_Runtime.new),
      ],
    );
    addTearDown(c.dispose);
    c.listen(chatAlertsProvider, (_, _) {});
    c.read(appForegroundProvider.notifier).set(foreground);
    return c;
  }

  test('deliveryFor keeps the normal build mapping', () {
    const prefs = NotifyPrefs();
    final alert = ChatAlert(
      host: HostRef.local,
      sessionId: 's1',
      kind: AlertKind.done,
      key: turnKey,
      title: 't',
      text: 'x',
    );
    expect(deliveryFor(alert, prefs: prefs, foreground: false), AlertDelivery.system);
    expect(deliveryFor(alert, prefs: prefs, foreground: true), AlertDelivery.banner);
  });

  group('remote Root queue', () {
    testWidgets('a denied post stays available and banners in the foreground',
        (tester) async {
      container = make(foreground: true);
      remote.emit(approval('e1', 'r1'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(container.read(chatAlertsProvider)?.key, 'e1');
      expect(container.read(chatAlertsProvider)?.eventId, 'e1');
    });

    testWidgets('an in-flight Root retry stops the whole coordinator', (tester) async {
      container = make(foreground: false);
      final gate = Completer<NotifyShowResult>();
      channel.showReply = (_) => gate.future;
      remote.emit(question('q1', 'r1', ['继续吗？']));
      await tester.pump(const Duration(milliseconds: 50));
      final retrying = container.read(chatAlertsProvider.notifier).retryFailedNotifications();
      await tester.pump();
      expect(retrying, isNotNull);
      gate.complete(const NotifyShowResult(delivered: false, reason: 'denied'));
      expect(await retrying, isFalse);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('retry delivers the original valid alert once', (tester) async {
      container = make(foreground: false);
      channel.showReply = (_) async => const NotifyShowResult(delivered: false, reason: 'denied');
      remote.emit(question('q1', 'r1', ['继续吗？']));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.shown, hasLength(1));
      channel.showReply = null;
      final delivered = await container
          .read(chatAlertsProvider.notifier)
          .retryFailedNotifications();
      expect(delivered, isTrue);
      expect(channel.shown, hasLength(2));
      expect(channel.shown.last.key, 'q1');
      expect(channel.shown.last.eventId, 'q1');
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
      expect(channel.shown, hasLength(2));
    });

    testWidgets('read or cancelled requests never come back late', (tester) async {
      container = make(foreground: false);
      channel.showReply = (_) async => const NotifyShowResult(delivered: false, reason: 'denied');
      remote.emit(approval('e1', 'r1'));
      await tester.pump(const Duration(milliseconds: 50));
      remote.emit(const HostRequestCancelledEvent('e1'));
      await tester.pump(const Duration(milliseconds: 50));
      channel.showReply = null;
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('withdrawal cancels an already delivered approval notification', (tester) async {
      container = make(foreground: false);
      remote.emit(approval('e1', 'r1'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.shown, hasLength(1));
      expect(channel.cancelled, isEmpty);

      remote.emit(const HostRequestCancelledEvent('e1'));
      await tester.pump(const Duration(milliseconds: 50));

      expect(channel.cancelled, contains(('ssh:lab|r1', 'e1')));
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('withdrawal while Root show waits cancels its late success', (tester) async {
      container = make(foreground: false);
      final pending = Completer<NotifyShowResult>();
      channel.showReply = (_) => pending.future;
      remote.emit(approval('e1', 'r1'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.shown, hasLength(1));

      remote.emit(const HostRequestCancelledEvent('e1'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.cancelled, contains(('ssh:lab|r1', 'e1')));

      pending.complete(const NotifyShowResult(delivered: true));
      await tester.pump(const Duration(milliseconds: 50));
      expect(container.read(chatAlertsProvider), isNull);
      expect(channel.cancelled, contains(('ssh:lab|r1', 'e1')));
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('a removed session never comes back late', (tester) async {
      container = make(foreground: false);
      channel.showReply = (_) async => const NotifyShowResult(delivered: false, reason: 'denied');
      remote.emit(question('q1', 'r1', ['继续吗？']));
      await tester.pump(const Duration(milliseconds: 50));
      remote.emit(const SessionRemovedEvent('r1'));
      await tester.pump(const Duration(milliseconds: 50));
      channel.showReply = null;
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('the foreground banner survives a background round trip',
        (tester) async {
      container = make(foreground: true);
      channel.showReply = (_) async => const NotifyShowResult(delivered: false, reason: 'denied');
      remote.emit(question('q1', 'r1', ['继续吗？']));
      await tester.pump(const Duration(milliseconds: 50));
      expect(container.read(chatAlertsProvider)?.key, 'q1');
      container.read(appForegroundProvider.notifier).set(false);
      await tester.pump(const Duration(milliseconds: 50));
      expect(container.read(chatAlertsProvider), isNull,
          reason: 'the post stays queued instead of dropping a duplicate');
      container.read(appForegroundProvider.notifier).set(true);
      await tester.pump(const Duration(milliseconds: 50));
      expect(container.read(chatAlertsProvider)?.key, 'q1');
    });

    testWidgets('a cancelled reason clears the queue entry', (tester) async {
      container = make(foreground: false);
      channel.showReply = (_) async => const NotifyShowResult(delivered: false, reason: 'cancelled');
      remote.emit(question('q1', 'r1', ['继续吗？']));
      await tester.pump(const Duration(milliseconds: 50));
      channel.showReply = null;
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('a denied done alert remains pending for notification recovery', (tester) async {
      container = make(foreground: false);
      channel.showReply = (_) async => const NotifyShowResult(delivered: false, reason: 'denied');
      remote.sessions = [session('r1', running: true)];
      remote.outcomes['r1'] = outcome(7, 'completed', reply: '完成');
      remote.emit(SessionStatusEvent('r1', true));
      await tester.pump();
      remote.emit(SessionStatusEvent('r1', false));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.shown, hasLength(1));
      channel.showReply = null;
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isTrue);
      expect(channel.shown, hasLength(2));
      expect(channel.shown.last.key, turnKey);
      expect(channel.shown.last.eventId, isNull);
    });

    testWidgets('switching hosts retires the old in-flight turn notification', (tester) async {
      container = make(foreground: false, runningSession: true);
      final pending = Completer<NotifyShowResult>();
      channel.showReply = (_) => pending.future;
      remote.outcomes['r1'] = outcome(8, 'completed', reply: '已完成');
      remote.emit(SessionStatusEvent('r1', false));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.shown, hasLength(1));
      expect(channel.shown.single.key, turnKey);
      expect(channel.shown.single.eventId, isNull);

      final nextConnection = HostConnection(
        client: _Client(),
        label: '另一台远程主机',
        isLocal: false,
        kind: HostKind.ssh,
        hostId: 'lab-b',
      );
      container
          .read(_currentConnectionProvider.notifier)
          .set(AsyncData(nextConnection));
      await tester.pump(const Duration(milliseconds: 50));
      pending.complete(const NotifyShowResult(delivered: true));
      await tester.pump(const Duration(milliseconds: 50));

      expect(channel.cancelled, contains(('ssh:lab|r1', turnKey)));
      expect(container.read(chatAlertsProvider), isNull);
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('resuming during an in-flight Root turn post restores its banner', (tester) async {
      container = make(foreground: false, runningSession: true);
      final pending = Completer<NotifyShowResult>();
      channel.showReply = (_) => pending.future;
      remote.outcomes['r1'] = outcome(9, 'completed', reply: '已完成');
      remote.emit(SessionStatusEvent('r1', false));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.shown, hasLength(1));
      expect(container.read(chatAlertsProvider), isNull);

      container.read(appForegroundProvider.notifier).set(true);
      await tester.pump(const Duration(milliseconds: 50));
      pending.complete(const NotifyShowResult(delivered: true));
      await tester.pump(const Duration(milliseconds: 50));

      expect(channel.cancelled, contains(('ssh:lab|r1', turnKey)));
      expect(container.read(chatAlertsProvider)?.key, turnKey);
      expect(channel.shown, hasLength(1));
    });

    testWidgets('MissingPlugin style failures remain pending in Root builds', (tester) async {
      container = make(foreground: false);
      channel.showReply = (_) async => const NotifyShowResult(delivered: false);
      remote.emit(approval('e1', 'r1'));
      await tester.pump(const Duration(milliseconds: 50));
      channel.showReply = null;
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isTrue);
      expect(channel.shown, hasLength(2));
    });

  });

  group('normal path', () {
    testWidgets('the legacy success still clears its own entry', (tester) async {
      container = make(foreground: false);
      remote.emit(approval('e1', 'r1'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(channel.shown, hasLength(1));
      expect(await container.read(chatAlertsProvider.notifier).retryFailedNotifications(), isFalse);
    });
  });
}

