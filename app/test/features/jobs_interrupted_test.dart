import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/state/features_state.dart' show jobsProvider;
import 'package:dsh_app/ui/features/jobs_page.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart' show AsyncData;
import 'package:flutter_test/flutter_test.dart';

import '../chat/support/harness.dart';
import '../plugins/support/fake_plugin_host.dart';

class _JobLink extends FakeMuxLink {
  Map<String, Object?>? followArgs;

  @override
  void open(String streamId, String endpoint, Map<String, Object?> payload) {
    if (endpoint == 'job/follow') {
      followArgs = payload;
    } else {
      super.open(streamId, endpoint, payload);
    }
  }
}

class _Carrier extends FakePluginCarrier {
  final jobs = _JobLink();

  @override
  Future<DshMuxLink> openLink(MuxOptions options) async => jobs;
}

void main() {
  testWidgets('interrupted row is inert; live row still opens output and stops', (tester) async {
    final carrier = _Carrier();
    carrier.handlers['job/kill'] = (_) => {'outcome': 'requested'};
    final client = DshClient.withCarrier(carrier);
    addTearDown(client.close);
    usePhoneView(tester);
    const jobs = [
      JobInfo(id: 'old-job', kind: 'bash', label: 'old interrupted build', status: 'interrupted'),
      JobInfo(id: 'live-job', kind: 'bash', label: 'live build', status: 'running'),
    ];
    await tester.pumpWidget(
      testApp(
        const JobsPage(sessionId: 's1'),
        overrides: [
          activeConnectionProvider.overrideWithValue(AsyncData(fakeHost(client))),
          jobsProvider.overrideWith((ref, id) => Stream.value(jobs)),
        ],
      ),
    );
    await pumpFrames(tester, 6);
    expect(find.text('old interrupted build'), findsOneWidget);
    expect(find.text('live build'), findsOneWidget);

    await tester.tap(find.text('old interrupted build'));
    await pumpFrames(tester, 4);
    expect(find.byType(JobOutputPage), findsNothing);
    expect(carrier.argsOf('job/follow'), isEmpty);
    expect(carrier.argsOf('job/kill'), isEmpty);

    await tester.tap(find.text('live build'));
    await pumpFrames(tester, 4);
    expect(find.byType(JobOutputPage), findsOneWidget);
    expect((carrier.jobs.followArgs!['args']! as Map)['request']! as Map, containsPair('jobId', 'live-job'));

    await tester.tap(find.byTooltip('停止'));
    await pumpFrames(tester, 2);
    await tester.tap(find.widgetWithText(FilledButton, '停止'));
    await pumpFrames(tester, 4);
    final kill = carrier.argsOf('job/kill').single['request']! as Map;
    expect(kill['jobId'], 'live-job');
    expect(kill['sessionId'], 's1');
    expect(carrier.calls.where((call) => call.$1 == 'job/kill').length, 1);
  });
}
