import 'dart:async';

import 'package:dsh_app/state/access_state.dart';
import 'package:dsh_app/state/phone_state.dart';
import 'package:dsh_app/state/settings_state.dart';
import 'package:dsh_app/ui/onboarding/access_step.dart';
import 'package:dsh_app/ui/settings/system_access_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../shell/golden_harness.dart';
import 'support.dart';

const _rootRuntime = bool.fromEnvironment('DSH_ROOT_RUNTIME');
const _primary = ValueKey('access-primary');

Future<void> _frames(WidgetTester tester, [int n = 8]) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<ProviderContainer> _pump(
  WidgetTester tester,
  Widget home,
  FakeAccessDriver driver, {
  List<Override> overrides = const [],
}) async {
  usePhoneSurface(tester);
  await tester.pumpWidget(
    goldenApp(home: home, brightness: Brightness.light, overrides: [...accessOverrides(driver), ...overrides]),
  );
  await _frames(tester);
  return ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
}

Future<void> _tapText(WidgetTester tester, String text) async {
  await tester.ensureVisible(find.text(text).last);
  await tester.tap(find.text(text).last);
  await _frames(tester);
}

Future<void> _tapPrimary(WidgetTester tester) async {
  await tester.ensureVisible(find.byKey(_primary));
  await tester.tap(find.byKey(_primary));
  await _frames(tester);
}

void _expectChoosing(WidgetTester tester) {
  expect(find.byKey(const ValueKey('choosing')), findsOneWidget);
  expect(find.byKey(const ValueKey('walk')), findsNothing);
  expect(find.byKey(const ValueKey('summary')), findsNothing);
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('root runtime', () {
    testWidgets('root skips prompts but summary reflects actual misses', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(readingsWith(offPhone, rootRuntime: true, clearVendor: true));
      final channel = _RootPhoneChannel();
      final container = await _pump(
        tester,
        AccessStepPage(onDone: () {}),
        driver,
        overrides: [phoneChannelProvider.overrideWithValue(channel)],
      );

      await _tapText(tester, '读写手机里的文件');
      await tester.tap(find.byKey(const ValueKey('risk-confirm')));
      await _frames(tester);
      await _tapText(tester, '手机操控');
      await _tapText(tester, '同意并开启');
      await _tapText(tester, '关键步骤先问我');
      await _tapText(tester, '继续');
      await _tapText(tester, '语音输入');
      await _tapPrimary(tester);

      expect(channel.enabled, isTrue);
      expect(find.text('还有 4 项没开'), findsOneWidget);
      for (final item in ['mic', 'storage', 'notifications', 'accessibility']) {
        final row = find.byKey(ValueKey('access-item-$item'));
        expect(row, findsOneWidget);
        expect(find.descendant(of: row, matching: find.text('未开启')), findsOneWidget);
      }
      expect(driver.opened, isEmpty);

      await tester.tap(find.byKey(const ValueKey('access-summary-done')));
      await _frames(tester);
      expect(container.read(settingsProvider).phoneStorage, isTrue);
    });

    testWidgets('root waits through preference, enable, and status readback', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(readingsWith(offPhone, rootRuntime: true, clearVendor: true));
      final chooseRequested = Completer<void>();
      final allowPreferences = Completer<void>();
      final allowEnable = Completer<void>();
      final statusRequested = Completer<void>();
      final allowStatus = Completer<void>();
      final channel = _RootPhoneChannel(
        enableRequested: Completer<void>(),
        allowEnable: allowEnable,
        statusRequested: statusRequested,
        allowStatus: allowStatus,
      );
      await _pump(
        tester,
        AccessStepPage(onDone: () {}),
        driver,
        overrides: [
          phoneChannelProvider.overrideWithValue(channel),
          accessSetupProvider.overrideWith(() => _DelayedAccessSetup(chooseRequested, allowPreferences)),
        ],
      );

      await _tapText(tester, '读写手机里的文件');
      await tester.tap(find.byKey(const ValueKey('risk-confirm')));
      await _frames(tester);
      await _tapText(tester, '手机操控');
      await _tapText(tester, '同意并开启');
      await _tapText(tester, '关键步骤先问我');
      await _tapText(tester, '继续');
      await _tapText(tester, '语音输入');
      await _tapPrimary(tester);

      expect(chooseRequested.isCompleted, isTrue);
      _expectChoosing(tester);

      allowPreferences.complete();
      await _frames(tester);
      expect(channel.enableRequested?.isCompleted, isTrue);
      _expectChoosing(tester);

      allowEnable.complete();
      await _frames(tester);
      expect(statusRequested.isCompleted, isTrue);
      _expectChoosing(tester);

      driver.readings = const AccessReadings(
        rootRuntime: true,
        mic: true,
        storage: true,
        notifications: true,
        accessibility: true,
      );
      allowStatus.complete();
      await _frames(tester, 16);

      expect(channel.enabled, isTrue);
      expect(find.text('都开好了'), findsOneWidget);
      expect(find.byKey(const ValueKey('access-item-storage')), findsNothing);
      for (final item in ['mic', 'notifications', 'accessibility']) {
        expect(
          find.descendant(of: find.byKey(ValueKey('access-item-$item')), matching: find.text('已开启')),
          findsOneWidget,
          reason: item,
        );
      }
    });

    testWidgets('system access hides granted root permissions but keeps failures', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(
        readingsWith(
          offPhone,
          rootRuntime: true,
          clearVendor: true,
          mic: true,
          battery: true,
          accessibility: true,
          overlay: true,
        ),
      );
      await _pump(tester, const SystemAccessPage(), driver);

      expect(find.text('允许使用麦克风'), findsNothing);
      expect(find.text('允许在后台运行'), findsNothing);
      expect(find.text('打开无障碍'), findsNothing);
      expect(find.text('允许显示在其他应用上层'), findsNothing);
      expect(find.text('允许访问文件'), findsOneWidget);
      expect(find.text('允许通知'), findsNWidgets(2));
    });

    testWidgets('system access keeps root permissions with unknown status', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(const AccessReadings(rootRuntime: true));
      await _pump(tester, const SystemAccessPage(), driver);

      for (final item in ['mic', 'storage', 'notifications', 'battery', 'accessibility', 'overlay']) {
        expect(find.byKey(ValueKey('access-item-$item')), findsWidgets, reason: item);
      }
    });

    testWidgets('root summary counts null grants and enum unknown as unresolved', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(const AccessReadings(vendor: 'vivo', rootRuntime: true));
      final container = await _pump(tester, AccessStepPage(onDone: () {}), driver);
      final setup = container.read(accessSetupProvider.notifier);
      await setup.choose({AccessFeature.background});
      await setup.skip(AccessItem.vendorBackground);
      await setup.skip(AccessItem.autostart);
      await _frames(tester);

      expect(find.text('还有 4 项没开'), findsOneWidget);
      for (final item in ['notifications', 'battery', 'vendorBackground', 'autostart']) {
        final row = find.byKey(ValueKey('access-item-$item'));
        expect(row, findsOneWidget, reason: item);
        expect(
          find.descendant(of: row, matching: find.text('去设置')),
          findsOneWidget,
          reason: item,
        );
      }
    });

    testWidgets('root summary reflects partial readback', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(
        const AccessReadings(rootRuntime: true, mic: true, storage: false, notifications: true, accessibility: false),
      );
      final container = await _pump(tester, AccessStepPage(onDone: () {}), driver);
      await container.read(accessSetupProvider.notifier).choose({
        AccessFeature.files,
        AccessFeature.phone,
        AccessFeature.voice,
      });
      await _frames(tester);

      expect(find.text('还有 2 项没开'), findsOneWidget);
      expect(
        find.descendant(of: find.byKey(const ValueKey('access-item-mic')), matching: find.text('已开启')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: find.byKey(const ValueKey('access-item-storage')), matching: find.text('未开启')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: find.byKey(const ValueKey('access-item-notifications')), matching: find.text('已开启')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: find.byKey(const ValueKey('access-item-accessibility')), matching: find.text('未开启')),
        findsOneWidget,
      );
    });

    // 默认非 root：存储步骤与授权由 access_flow_test.dart 覆盖。
  });

  testWidgets('ordinary system access still omits null readings', (tester) async {
    final driver = FakeAccessDriver(const AccessReadings());
    await _pump(tester, const SystemAccessPage(), driver);

    for (final item in ['mic', 'storage', 'notifications', 'battery', 'accessibility', 'overlay']) {
      expect(find.byKey(ValueKey('access-item-$item')), findsNothing, reason: item);
    }
  });
}

class _RootPhoneChannel extends PhoneChannel {
  _RootPhoneChannel({this.enableRequested, this.allowEnable, this.statusRequested, this.allowStatus});

  final Completer<void>? enableRequested;
  final Completer<void>? allowEnable;
  final Completer<void>? statusRequested;
  final Completer<void>? allowStatus;
  bool enabled = false;
  bool _holdStatus = false;

  @override
  Future<PhoneStatus> status() async {
    if (_holdStatus) {
      if (statusRequested case final requested? when !requested.isCompleted) requested.complete();
      await allowStatus?.future;
      return PhoneStatus(rootRuntime: true, enabled: enabled);
    }
    return const PhoneStatus.notReady();
  }

  @override
  Future<bool> setEnabled(bool value) async {
    if (enableRequested case final requested? when !requested.isCompleted) requested.complete();
    await allowEnable?.future;
    enabled = value;
    _holdStatus = allowStatus != null;
    return true;
  }
}

class _DelayedAccessSetup extends AccessSetupController {
  _DelayedAccessSetup(this.chooseRequested, this.allowPreferences);

  final Completer<void> chooseRequested;
  final Completer<void> allowPreferences;

  @override
  Future<void> choose(Set<AccessFeature> features, {PhoneConfirmMode? phoneMode}) async {
    state = AccessSetup(loaded: true, features: {...features});
    chooseRequested.complete();
    await allowPreferences.future;
    await super.choose(features, phoneMode: phoneMode);
  }
}
