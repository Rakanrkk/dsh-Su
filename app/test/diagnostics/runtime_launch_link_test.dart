import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/settings_state.dart';
import 'package:dsh_app/ui/settings/diagnostics_page.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../shell/golden_harness.dart';

const _rootStatus = RuntimeStatus(
  phase: RuntimePhase.running,
  baseUrl: 'http://127.0.0.1:3083',
  token: 'fixture-token-one',
  rootRuntime: true,
);

class _Runtime extends LocalRuntimeController {
  _Runtime(this.initial);

  final RuntimeStatus initial;

  @override
  LocalRuntimeState build() => LocalRuntimeState(loaded: true, status: initial);

  void update(RuntimeStatus status) => state = state.copyWith(status: status);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('copies only the current Root runtime URL on explicit tap', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(800, 1200);
    addTearDown(tester.view.reset);
    final clipboard = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') clipboard.add((call.arguments as Map)['text'] as String);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final runtime = _Runtime(_rootStatus);

    await tester.pumpWidget(
      goldenApp(
        home: const DiagnosticsPage(),
        brightness: Brightness.light,
        overrides: [
          localRuntimeProvider.overrideWith(() => runtime),
          localRuntimeLogsProvider.overrideWithBuild((ref, notifier) => const []),
          settingsProvider.overrideWith(() => FakeSettings(const AppSettings(loaded: true))),
        ],
      ),
    );
    await tester.pumpAndSettle();

    final container = ProviderScope.containerOf(tester.element(find.byType(DiagnosticsPage)), listen: false);
    expect(container.read(localRuntimeProvider).status.rootRuntime, isTrue);
    expect(
      DiagnosticsPage.localRuntimeLaunchUri(_rootStatus)?.toString(),
      'http://127.0.0.1:3083/?token=fixture-token-one',
    );
    expect(find.text('http://127.0.0.1:3083'), findsOneWidget);
    expect(find.text('复制链接'), findsOneWidget);
    expect(find.textContaining('fixture-token-one'), findsNothing);
    final copyLink = find.text('复制链接');
    await tester.ensureVisible(copyLink);
    await tester.tap(copyLink);
    await tester.pump();
    expect(clipboard.single, 'http://127.0.0.1:3083/?token=fixture-token-one');

    runtime.update(
      const RuntimeStatus(
        phase: RuntimePhase.running,
        baseUrl: 'http://127.0.0.1:3084',
        token: 'fixture-token-two',
        rootRuntime: true,
      ),
    );
    await tester.pump();
    await tester.ensureVisible(copyLink);
    await tester.tap(copyLink);
    await tester.pump();
    expect(clipboard.last, 'http://127.0.0.1:3084/?token=fixture-token-two');

    final copyDiagnostics = find.text('复制诊断信息');
    await tester.ensureVisible(copyDiagnostics);
    await tester.tap(copyDiagnostics);
    await tester.pump();
    expect(clipboard.last, isNot(contains('fixture-token-two')));
    expect(clipboard.last, contains('<redacted>'));
  });

  testWidgets('hides launch URL copy for non-Root, stopped, and non-loopback status', (tester) async {
    for (final status in [
      const RuntimeStatus(phase: RuntimePhase.running, baseUrl: 'http://127.0.0.1:3083', token: 'fixture-remote-token'),
      const RuntimeStatus(
        phase: RuntimePhase.installed,
        baseUrl: 'http://127.0.0.1:3083',
        token: 'fixture-stale-token',
        rootRuntime: true,
      ),
      const RuntimeStatus(
        phase: RuntimePhase.running,
        baseUrl: 'https://remote.example',
        token: 'fixture-selected-remote-token',
        rootRuntime: true,
      ),
    ]) {
      await tester.pumpWidget(
        goldenApp(
          home: const DiagnosticsPage(),
          brightness: Brightness.light,
          overrides: [
            localRuntimeProvider.overrideWith(() => _Runtime(status)),
            localRuntimeLogsProvider.overrideWithBuild((ref, notifier) => const []),
            settingsProvider.overrideWith(() => FakeSettings(const AppSettings(loaded: true))),
          ],
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('复制链接'), findsNothing);
      expect(find.textContaining('fixture-'), findsNothing);
    }
  });
}
