import 'dart:convert';
import 'dart:async';

import 'package:dsh_app/state/hosts_state.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/state/features_state.dart';
import 'package:dsh_app/state/job_watch_state.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_platform_interface.dart';

import '../plugins/support/fake_plugin_host.dart';

JobInfo job(String id, {String status = 'running'}) => JobInfo(
  id: id,
  kind: 'bash',
  label: 'run $id',
  status: status,
  startedAt: DateTime(2026, 11, 5, 12),
);

MuxGeneration generation(String instance) => MuxGeneration(1, 'client-1', '/home/u', hostInstanceId: instance);

Future<void> settle() async {
  for (var i = 0; i < 6; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

ProviderContainer providerContainer(DshClient client) => ProviderContainer(
  overrides: [
    activeHostProvider.overrideWith(() => _FixedHost()),
    activeConnectionProvider.overrideWithValue(AsyncData(fakeHost(client))),
  ],
);

var _releaseGatedSave = false;

class _FixedHost extends ActiveHostController {
  @override
  HostRef build() => HostRef.ssh('remote');
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('same instance reconnect does not mark or rewrite observations', () async {
    final carrier = FakePluginCarrier(hostInstanceId: 'host-1');
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final controller = JobWatchController(JobObservationStore(JobObservationStore.keyFor(HostRef.local, 's1')));
    await controller.load();
    final first = await controller.reconcile(generation('host-1'), [job('j1')], now: () => DateTime(2026, 11, 5, 12, 1));
    expect(first.where((job) => job.status == 'interrupted'), isEmpty);
    final same = await controller.reconcile(generation('host-1'), [job('j1')], now: () => DateTime(2026, 11, 5, 12, 2));
    expect(same.where((job) => job.status == 'interrupted'), isEmpty);
    final stored = jsonDecode((await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.local, 's1'))!);
    expect(stored['hostInstanceId'], 'host-1');
    expect(stored['jobs'].keys, ['j1']);
    expect(stored['interrupted'], isEmpty);
  });

  test('a new instance marks prior live observations interrupted', () async {
    final carrier = FakePluginCarrier(hostInstanceId: 'host-1');
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final controller = JobWatchController(JobObservationStore(JobObservationStore.keyFor(HostRef.local, 's1')));
    await controller.load();
    await controller.reconcile(generation('host-1'), [job('j1'), job('j2', status: 'completed')]);
    final after = await controller.reconcile(generation('host-2'), [job('j3')], now: () => DateTime(2026, 11, 5, 12, 5));
    final interrupted = after.where((job) => job.status == 'interrupted').single;
    expect(interrupted.id, 'j1');
    expect(interrupted.finishedAt, DateTime(2026, 11, 5, 12, 5));
    expect(after.where((job) => job.id == 'j3' && job.status == 'running'), isNotEmpty);
    final stored = jsonDecode((await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.local, 's1'))!);
    expect(stored['hostInstanceId'], 'host-2');
    expect(stored['jobs'].keys, ['j3']);
  });

  test('a missing host instance never marks observations', () async {
    final carrier = FakePluginCarrier();
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      JobObservationStore.keyFor(HostRef.local, 's1'),
      jsonEncode({
        'hostInstanceId': 'host-1',
        'jobs': {
          'j1': {'id': 'j1', 'kind': 'bash', 'label': 'run j1', 'startedAt': 1762337600000},
        },
      }),
    );
    final controller = JobWatchController(JobObservationStore(JobObservationStore.keyFor(HostRef.local, 's1')));
    await controller.load();
    final jobs = await controller.reconcile(const MuxGeneration(1, 'client-1', '/home/u'), [job('j2')]);
    expect(jobs.where((job) => job.status == 'interrupted'), isEmpty);
    expect(jobs.where((job) => job.id == 'j2' && job.status == 'running'), isNotEmpty);
  });

  test('legacy stored live jobs wait for identity evidence', () async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      JobObservationStore.keyFor(HostRef.local, 's1'),
      jsonEncode({
        'hostInstanceId': 'host-1',
        'jobs': {
          'j1': {'id': 'j1', 'kind': 'bash', 'label': 'run j1', 'startedAt': 1762337600000},
        },
      }),
    );
    final controller = JobWatchController(JobObservationStore(JobObservationStore.keyFor(HostRef.local, 's1')));
    await controller.load();
    expect(controller.interrupted, isEmpty);
    final jobs = await controller.reconcile(null, [job('j2')]);
    expect(jobs.single.id, 'j2');
    expect(jobs.single.status, 'running');
  });

  test('load restores confirmed interrupted history', () async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      JobObservationStore.keyFor(HostRef.local, 's1'),
      jsonEncode({
        'hostInstanceId': 'host-2',
        'jobs': {},
        'interrupted': {
          'j1': {
            'observation': {'id': 'j1', 'kind': 'bash', 'label': 'run j1', 'startedAt': 1762337600000},
            'interruptedAt': 1762337660000,
          },
        },
      }),
    );
    final controller = JobWatchController(JobObservationStore(JobObservationStore.keyFor(HostRef.local, 's1')));
    await controller.load();
    final jobs = await controller.reconcile(generation('host-2'), [job('j2')]);
    final interrupted = jobs.where((job) => job.status == 'interrupted').single;
    expect(interrupted.id, 'j1');
    expect(interrupted.finishedAt, DateTime.fromMillisecondsSinceEpoch(1762337660000));
    expect(controller.interrupted, isNotEmpty);
  });

  test('stored observations restore and mark when a restored identity differs', () async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      JobObservationStore.keyFor(HostRef.local, 's1'),
      jsonEncode({
        'hostInstanceId': 'host-1',
        'jobs': {
          'j1': {'id': 'j1', 'kind': 'bash', 'label': 'run j1', 'startedAt': 1762337600000, 'progress': 'warm'},
        },
      }),
    );
    final controller = JobWatchController(JobObservationStore(JobObservationStore.keyFor(HostRef.local, 's1')));
    await controller.load();
    final jobs = await controller.reconcile(generation('host-2'), [job('j2')], now: () => DateTime(2026, 11, 5, 12, 9));
    final interrupted = jobs.where((job) => job.status == 'interrupted').single;
    expect(interrupted.id, 'j1');
    expect(interrupted.progress, 'warm');
    expect(interrupted.finishedAt, DateTime(2026, 11, 5, 12, 9));
  });

  test('the provider uses the logical host and session observation key', () async {
    final first = FakePluginCarrier(hostInstanceId: 'host-1');
    final firstClient = DshClient.withCarrier(first, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(firstClient.close);
    await firstClient.mux.ready();

    final key = JobObservationStore.keyFor(HostRef.ssh('remote'), 's1');
    final controller = JobWatchController(JobObservationStore(key));
    await controller.load();
    await controller.reconcile(firstClient.mux.generation, [job('j1')]);
    final second = FakePluginCarrier(hostInstanceId: 'host-2');
    final secondClient = DshClient.withCarrier(second, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(secondClient.close);
    await secondClient.mux.ready();

    final after = await controller.reconcile(secondClient.mux.generation, [job('j2')], now: () => DateTime(2026, 11, 5, 12, 12));
    expect(after.where((job) => job.status == 'interrupted').single.id, 'j1');
    expect((await SharedPreferences.getInstance()).getString(key), isNotNull);
  });

  testWidgets('the real provider reconnects across generations without a stale stream blocking the next', (tester) async {
    final carrier = FakePluginCarrier(hostInstanceId: 'host-1');
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final container = providerContainer(client);
    addTearDown(container.dispose);
    final sub = container.listen(jobsProvider('s1'), (_, _) {});
    addTearDown(sub.close);
    expect(await container.read(jobsProvider('s1').future), isEmpty);

    final link = carrier.link!;
    final newLink = FakeMuxLink(hostInstanceId: 'host-2');
    carrier.link = newLink;
    await link.close(4000, 'lost');
    await tester.pump(Duration.zero);
    expect(container.read(jobsProvider('s1')).hasValue, isTrue);
    expect(newLink.started, isTrue);
    final second = await container.read(jobsProvider('s1').future);
    expect(second, hasLength(1));
  });

  testWidgets('provider cancellation leaves no pending watch or late stale set', (tester) async {
    final carrier = FakePluginCarrier(hostInstanceId: 'host-1');
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final container = providerContainer(client);
    addTearDown(container.dispose);
    final sub = container.listen(jobsProvider('s1'), (_, _) {});
    expect(await container.read(jobsProvider('s1').future), isEmpty);
    sub.close();

    final next = FakeMuxLink(hostInstanceId: 'host-2');
    carrier.link = next;
    await carrier.link!.close(4000, 'lost');
    await tester.pump(Duration.zero);
    expect(next.started, isFalse);
  });

  testWidgets('a queued old generation does not persist or emit after cancellation', (tester) async {
    final carrier = FakePluginCarrier(hostInstanceId: 'host-1');
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final container = providerContainer(client);
    addTearDown(container.dispose);
    final sub = container.listen(jobsProvider('s1'), (_, _) {});
    expect(await container.read(jobsProvider('s1').future), isEmpty);

    final link = carrier.link!;
    final next = FakeMuxLink(hostInstanceId: 'host-2');
    carrier.link = next;
    await link.close(4000, 'lost');
    await tester.pump(Duration.zero);
    sub.close();

    SharedPreferences.setMockInitialValues({});
    final lateStore = JobObservationStore(
      JobObservationStore.keyFor(HostRef.ssh('remote'), 's1'),
      isCurrent: () => false,
    );
    final lateJobs = await lateStore
        .save(JobObservations(hostInstanceId: 'host-1', jobs: {'j1': ObservedJob.fromJob(job('j1'))}, loaded: true))
        .then((_) async => await SharedPreferences.getInstance());
    expect(lateJobs.getString(JobObservationStore.keyFor(HostRef.ssh('remote'), 's1')), isNull);
    expect(container.read(jobsProvider('s1')).hasValue, isFalse);
  });

  testWidgets('a save waiting past a new generation does not write or emit an empty set', (tester) async {
    final carrier = FakePluginCarrier(hostInstanceId: 'host-1');
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    _releaseGatedSave = false;
    SharedPreferencesStorePlatform.instance = _GatedSharedPreferencesStore(SharedPreferencesStorePlatform.instance);
    addTearDown(() {
      _releaseGatedSave = true;
      SharedPreferences.setMockInitialValues({});
    });
    final container = providerContainer(client);
    addTearDown(container.dispose);
    final sub = container.listen(jobsProvider('s1'), (_, _) {});
    addTearDown(sub.close);
    expect(await container.read(jobsProvider('s1').future), isEmpty);

    final oldLink = carrier.link!;
    final newLink = FakeMuxLink(hostInstanceId: 'host-2');
    carrier.link = newLink;
    await oldLink.close(4000, 'lost');
    await tester.pump(Duration.zero);
    expect(newLink.started, isTrue);
    expect(container.read(jobsProvider('s1')).hasValue, isFalse);

    _releaseGatedSave = true;
    await tester.pump();
    await tester.pump();
    final stored = (await SharedPreferences.getInstance()).getString(
      JobObservationStore.keyFor(HostRef.ssh('remote'), 's1'),
    );
    expect(stored, isNull);
    expect(container.read(jobsProvider('s1')).value, isNot(isEmpty));
  });
}

class _GatedSharedPreferencesStore with MockPlatformInterfaceMixin implements SharedPreferencesStorePlatform {
  _GatedSharedPreferencesStore(this._next);

  final SharedPreferencesStorePlatform _next;
  final _pending = <Completer<void>>[];

  @override
  bool get isMock => true;

  @override
  Future<bool> setValue(String valueType, String key, Object value) async {
    final gate = Completer<void>();
    _pending.add(gate);
    await gate.future;
    if (!_releaseGatedSave) return false;
    return _next.setValue(valueType, key, value);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) {
    if (invocation.memberName == #getAll) return _next.getAll();
    if (invocation.memberName == #getAllWithParameters) {
      return Function.apply(_next.getAllWithParameters, invocation.positionalArguments);
    }
    return super.noSuchMethod(invocation);
  }
}
