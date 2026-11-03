// What NotifyChannel.show reports and how tryShow treats Root waits: a null
// answer is the legacy success, a false map keeps denied / cancelled, channel
// failures never throw, and Root skips the status short-circuit and the 3 s
// show timeout.

import 'dart:async';

import 'package:dsh_app/state/hosts_state.dart';
import 'package:dsh_app/state/notify_state.dart';

import 'support.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _postBase = NotifyPost(
  group: 'ssh:lab|s1',
  key: 'e1',
  attention: true,
  title: 't',
  text: 'x',
  publicText: '等你确认',
  sessionId: 's1',
  host: HostRef.ssh('lab'),
  kind: 'approval',
  eventId: 'e1',
);

NotifyPost _post() => _postBase;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  NotifyChannel? channel;
  FakeNotifyChannel? fake;
  const methods = MethodChannel('test/notify-show');
  late TestDefaultBinaryMessenger messenger;

  setUp(() {
    messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    channel = NotifyChannel(methods: methods);
    fake = FakeNotifyChannel();
  });

  group('show', () {
    test('a null native answer is the legacy success', () async {
      messenger.setMockMethodCallHandler(methods, (call) async => null);
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
      final result = await channel!.show(_post());
      expect(result.delivered, isTrue);
      expect(result.reason, isNull);
    });

    test('a delivered map keeps the success without a reason', () async {
      messenger.setMockMethodCallHandler(
        methods,
        (call) async => {'delivered': true},
      );
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
      final result = await channel!.show(_post());
      expect(result.delivered, isTrue);
      expect(result.reason, isNull);
    });

    test('denied and cancelled stay in the result', () async {
      messenger.setMockMethodCallHandler(
        methods,
        (call) async => {'delivered': false, 'reason': 'denied'},
      );
      final denied = await channel!.show(_post());
      expect(denied.delivered, isFalse);
      expect(denied.reason, 'denied');
      messenger.setMockMethodCallHandler(
        methods,
        (call) async => {'delivered': false, 'reason': 'cancelled'},
      );
      final cancelled = await channel!.show(_post());
      expect(cancelled.delivered, isFalse);
      expect(cancelled.reason, 'cancelled');
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
    });

    test('a plain false map reports no reason', () async {
      messenger.setMockMethodCallHandler(
        methods,
        (call) async => {'delivered': false},
      );
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
      final result = await channel!.show(_post());
      expect(result.delivered, isFalse);
      expect(result.reason, isNull);
    });

    test('MissingPlugin and PlatformException never throw', () async {
      messenger.setMockMethodCallHandler(
        methods,
        (call) => throw MissingPluginException(),
      );
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
      var result = await channel!.show(_post());
      expect(result.delivered, isFalse);
      expect(result.reason, isNull);
      messenger.setMockMethodCallHandler(
        methods,
        (call) => throw PlatformException(code: 'root'),
      );
      result = await channel!.show(_post());
      expect(result.delivered, isFalse);
      expect(result.reason, isNull);
    });

    test('stillCurrent false reports cancelled', () async {
      final result = await channel!.show(
        _post(),
        stillCurrent: () => false,
      );
      expect(result.delivered, isFalse);
      expect(result.reason, 'cancelled');
    });
  });

  group('tryShow', () {
    test('the normal build keeps the status short-circuit and 3 s timeout',
        () async {
      final statusCalls = <MethodCall>[];
      final showCalls = <MethodCall>[];
      final showReply = Completer<Object?>();
      messenger.setMockMethodCallHandler(methods, (call) async {
        if (call.method == 'status') {
          statusCalls.add(call);
          return {'native': true, 'enabled': true, 'attention': true};
        }
        showCalls.add(call);
        return showReply.future;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
      final slow = channel!.tryShow(_post());
      await Future<void>.delayed(const Duration(milliseconds: 10));
      expect(statusCalls, hasLength(1));
      expect(showCalls, hasLength(1));
      expect(await slow, isFalse);
      showReply.complete({'delivered': true});
      expect(await slow, isFalse, reason: 'the normal 3 s show timeout already fired');
    });

    testWidgets('Root waits past the old 3 s show timeout', (tester) async {
      final statusCalls = <MethodCall>[];
      final showCalls = <MethodCall>[];
      final showReply = Completer<Object?>();
      messenger.setMockMethodCallHandler(methods, (call) async {
        if (call.method == 'status') {
          statusCalls.add(call);
          return {'native': true, 'enabled': true, 'attention': true};
        }
        showCalls.add(call);
        return showReply.future;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
      final slow = channel!.tryShow(_post(), rootRuntime: true);
      await tester.pump(const Duration(seconds: 4));
      expect(statusCalls, isEmpty);
      expect(showCalls, hasLength(1));
      if (!showReply.isCompleted) showReply.complete({'delivered': true});
      expect(await slow, isTrue);
    });

    test('Root delivery follows the same result mapping', () async {
      messenger.setMockMethodCallHandler(
        methods,
        (call) async => {'delivered': false, 'reason': 'denied'},
      );
      addTearDown(() => messenger.setMockMethodCallHandler(methods, null));
      expect(
        await channel!.tryShow(_post(), rootRuntime: true),
        isFalse,
      );
    });

    test('Root keeps the stillCurrent check', () async {
      expect(
        await channel!.tryShow(_post(), rootRuntime: true, stillCurrent: () => false),
        isFalse,
      );
    });

    test('the fake keeps the normal shape for controller tests', () async {
      expect(await fake!.tryShow(_post()), isTrue);
      fake!.system = const NotifySystemStatus(enabled: false);
      expect(await fake!.tryShow(_post()), isFalse);
    });
  });
}
