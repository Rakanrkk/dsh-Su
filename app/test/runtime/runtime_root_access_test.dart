import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('ensureRootAccess invokes the native setup method', () async {
    const channel = MethodChannel('test/root-access');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    var calls = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'ensureRootAccess');
      calls++;
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    await RuntimeBridge(methods: channel, events: const EventChannel('unused')).ensureRootAccess();
    expect(calls, 1);
  });

  test('native root failures surface as PlatformException', () async {
    const channel = MethodChannel('test/root-access-failure');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'ensureRootAccess');
      throw PlatformException(
        code: 'root_unavailable',
        message: 'cancelled',
        details: {'reason': 'cancelled', 'missing': <String>[]},
      );
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    expect(
      () => RuntimeBridge(methods: channel, events: const EventChannel('unused')).ensureRootAccess(),
      throwsA(isA<PlatformException>().having(
        (error) => error.code,
        'code',
        'root_unavailable',
      ).having((error) => error.details, 'details', {
        'reason': 'cancelled',
        'missing': <String>[],
      })),
    );
  });
}
