import 'package:dsh_app/state/access_state.dart';
import 'package:dsh_app/ui/onboarding/access_step.dart';
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

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('root runtime', () {
    testWidgets('storage granted at start needs no step; another item still asks', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(
        readingsWith(offPhone, storage: true),
        answer: (item, readings) => item == AccessItem.mic ? readingsWith(readings, mic: true) : readings,
      );
      await _pump(tester, AccessStepPage(onDone: () {}), driver);
      await _tapText(tester, '读写手机里的文件');
      await tester.tap(find.byKey(const ValueKey('risk-confirm')));
      await _frames(tester);
      await _tapText(tester, '语音输入');
      await _tapPrimary(tester);

      expect(find.text('需要文件访问权限'), findsNothing);
      expect(find.text('允许访问文件'), findsNothing);
      expect(find.text('允许使用麦克风'), findsOneWidget);
      expect(driver.opened, isEmpty);

      await _tapPrimary(tester);
      expect(driver.opened, ['mic']);
      expect(find.text('都开好了'), findsOneWidget);
    });

    testWidgets('storage not granted still asks; nothing reports granted early', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(offPhone);
      await _pump(tester, AccessStepPage(onDone: () {}), driver);
      await _tapText(tester, '读写手机里的文件');
      await tester.tap(find.byKey(const ValueKey('risk-confirm')));
      await _frames(tester);
      await _tapPrimary(tester);

      expect(find.text('允许访问文件'), findsOneWidget);
      expect(driver.opened, isEmpty);
    });

    testWidgets('storage alone does not hide an overlay that is still off', skip: !_rootRuntime, (tester) async {
      final driver = FakeAccessDriver(
        readingsWith(offPhone, storage: true),
        answer: (item, readings) =>
            item == AccessItem.overlay ? readingsWith(readings, overlay: true) : readings,
      );
      await _pump(tester, AccessStepPage(onDone: () {}), driver);
      await _tapText(tester, '读写手机里的文件');
      await tester.tap(find.byKey(const ValueKey('risk-confirm')));
      await _frames(tester);
      await _tapText(tester, '桌宠与悬浮窗');
      await _tapPrimary(tester);

      expect(find.text('允许显示在其他应用上层'), findsOneWidget);
      expect(driver.opened, isEmpty);

      await _tapPrimary(tester);
      await awayAndBack(tester);
      expect(driver.opened, ['overlay']);
      await _frames(tester, 10);
      expect(find.text('都开好了'), findsOneWidget);
    });

    // 默认非 root：存储步骤与授权由 access_flow_test.dart 覆盖。
  });
}
