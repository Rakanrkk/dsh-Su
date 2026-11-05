import 'package:dsh_app/state/phone_state.dart';
import 'package:dsh_app/ui/phone/phone_background_guide.dart';
import 'package:dsh_app/ui/phone/phone_control_screen.dart';
import 'package:dsh_app/ui/phone/phone_widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../shell/golden_harness.dart';
import 'harness.dart';
import 'support.dart';

const _rootRuntime = bool.fromEnvironment('DSH_ROOT_RUNTIME');

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('an existing disclosure acceptance still gets the new first-enable guide', (tester) async {
    SharedPreferences.setMockInitialValues({PhoneController.disclosureKey: true});
    final h = await pumpPhone(
      tester,
      const PhoneControlScreen(),
      native: FakePhoneNative(status: phoneStatus(enabled: false)),
    );
    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();
    expect(find.text('开启手机操控前'), findsNothing);
    expect(find.byKey(const Key('phone-background-guide')), findsOneWidget);
    expect(find.textContaining('后台弹出界面'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, '打开系统设置'));
    await tester.pumpAndSettle();
    expect(h.native!.callsOf('openAppDetails'), hasLength(1));
    expect((await SharedPreferences.getInstance()).getBool(phoneBackgroundGuideSeenKey), isTrue);
  });

  testWidgets('a blocked dedicated confirmation channel is not shown as allowed', skip: _rootRuntime, (tester) async {
    final h = await pumpPhone(
      tester,
      const PhoneControlScreen(),
      native: FakePhoneNative(status: {...phoneStatus(), 'confirmationNotifications': false}),
    );
    expect(find.text('无障碍服务'), findsOneWidget);
    expect(find.descendant(of: find.byType(PhoneStepRow), matching: find.byType(DoneMark)), findsNothing);
    expect(find.text('请允许手机操控通知的弹出提醒'), findsOneWidget);
    final row = find.ancestor(of: find.text('通知'), matching: find.byType(PhoneStepRow));
    expect(find.descendant(of: row, matching: find.byType(DoneMark)), findsNothing);
    await tester.tap(find.descendant(of: row, matching: find.text('去开启')));
    await tester.pump();
    expect(h.native!.callsOf('openAppDetails'), hasLength(1));
    h.native!.push({...phoneStatus(), 'confirmationNotifications': true});
    await settlePhone(tester);
    await tester.pumpAndSettle();
    expect(find.descendant(of: row, matching: find.byType(DoneMark)), findsOneWidget);
  });

  testWidgets('root runtime still shows failed phone readbacks as setup steps', skip: !_rootRuntime, (tester) async {
    await pumpPhone(
      tester,
      const PhoneControlScreen(),
      native: FakePhoneNative(
        status: {...phoneStatus(enabled: true, a11y: false), 'rootRuntime': true, 'confirmationNotifications': false},
      ),
    );
    expect(find.text('无障碍服务'), findsOneWidget);
    expect(find.widgetWithText(PhoneStepRow, '请允许手机操控通知的弹出提醒'), findsOneWidget);
    expect(find.descendant(of: find.byType(PhoneStepRow), matching: find.byType(DoneMark)), findsNothing);
    expect(find.textContaining('允许手机操控通知'), findsOneWidget);
  });

  testWidgets('the help remains scrollable on a narrow screen with large text', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(320, 568);
    addTearDown(tester.view.reset);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.runAsync(loadGoldenFonts);
    await tester.pumpWidget(
      goldenApp(
        brightness: Brightness.light,
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () => showPhoneBackgroundGuide(context, const PhoneDevice(manufacturer: 'vivo')),
                child: const Text('Help'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Help'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('phone-background-guide')), findsOneWidget);
    final dialogContext = tester.element(find.byKey(const Key('phone-background-guide')));
    expect(MediaQuery.textScalerOf(dialogContext).scale(10), 20);
    expect(tester.takeException(), isNull);
    await tester.drag(find.byType(SingleChildScrollView).last, const Offset(0, -300));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.widgetWithText(TextButton, '知道了'));
    await tester.tap(find.widgetWithText(TextButton, '知道了'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('phone-background-guide')), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
