import 'dart:convert';
import 'dart:async';

import 'package:dsh_app/state/hosts_state.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/state/features_state.dart';
import 'package:dsh_app/state/job_watch_state.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../plugins/support/fake_plugin_host.dart';

JobInfo job(String id, {String status = 'running'}) =>
    JobInfo(id: id, kind: 'bash', label: 'run $id', status: status, startedAt: DateTime(2026, 11, 5, 12));

MuxGeneration generation() => const MuxGeneration(1, 'client-1', '/home/u');

Future<void> settle() => Future<void>.delayed(Duration.zero);

ProviderContainer providerContainer(DshClient client) => ProviderContainer(
  overrides: [
    activeHostProvider.overrideWith(() => _FixedHost()),
    activeConnectionProvider.overrideWithValue(AsyncData(fakeHost(client))),
  ],
);

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
    final first = await controller.reconcile('host-1', [job('j1')], now: () => DateTime(2026, 11, 5, 12, 1));
    expect(first.where((job) => job.status == 'interrupted'), isEmpty);
    final same = await controller.reconcile('host-1', [job('j1')], now: () => DateTime(2026, 11, 5, 12, 2));
    expect(same.where((job) => job.status == 'interrupted'), isEmpty);
    final stored = jsonDecode(
      (await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.local, 's1'))!,
    );
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
    await controller.reconcile('host-1', [job('j1'), job('j2', status: 'completed')]);
    final after = await controller.reconcile('host-2', [job('j3')], now: () => DateTime(2026, 11, 5, 12, 5));
    final interrupted = after.where((job) => job.status == 'interrupted').single;
    expect(interrupted.id, 'j1');
    expect(interrupted.finishedAt, DateTime(2026, 11, 5, 12, 5));
    expect(after.where((job) => job.id == 'j3' && job.status == 'running'), isNotEmpty);
    final stored = jsonDecode(
      (await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.local, 's1'))!,
    );
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
    final jobs = await controller.reconcile(null, [job('j2')]);
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
    final jobs = await controller.reconcile('host-2', [job('j2')]);
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
    final jobs = await controller.reconcile('host-2', [job('j2')], now: () => DateTime(2026, 11, 5, 12, 9));
    final interrupted = jobs.where((job) => job.status == 'interrupted').single;
    expect(interrupted.id, 'j1');
    expect(interrupted.progress, 'warm');
    expect(interrupted.finishedAt, DateTime(2026, 11, 5, 12, 9));
  });

  test('the provider uses the logical host and session observation key', () async {
    final carrier = FakePluginCarrier(contributions: {'v': 1, 'hostInstanceId': 'host-1', 'plugins': const []});
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final container = providerContainer(client);
    addTearDown(container.dispose);
    await client.mux.ready();
    final events = <List<JobInfo>>[];
    final target = Completer<List<JobInfo>>();
    final sub = container.listen(jobsProvider('s1'), (_, state) {
      if (state.value case final list?) {
        events.add(list);
        if (!target.isCompleted) target.complete(list);
      }
    });
    addTearDown(sub.close);
    await carrier.link!.jobOpened;
    carrier.link!.pushJob('s1', [job('j1')]);
    final first = await target.future.timeout(const Duration(seconds: 1));
    expect(first.where((job) => job.id == 'j1' && job.status == 'running'), isNotEmpty);
    final key = JobObservationStore.keyFor(HostRef.ssh('remote'), 's1');
    final stored = jsonDecode((await SharedPreferences.getInstance()).getString(key)!);
    expect(stored['hostInstanceId'], 'host-1');
    expect(stored['jobs'].keys, ['j1']);
  });

  test('a jobs set before metadata stays unpersisted and reconciles when identity arrives', () async {
    final carrier = FakePluginCarrier(gateContributions: true);
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final container = providerContainer(client);
    addTearDown(container.dispose);
    await client.mux.ready();
    final events = <List<JobInfo>>[];
    final target = Completer<List<JobInfo>>();
    final secondData = Completer<List<JobInfo>>();
    final sub = container.listen(jobsProvider('s1'), (_, state) {
      if (state.value case final list?) {
        events.add(list);
        if (!target.isCompleted) {
          target.complete(list);
        } else if (!secondData.isCompleted) {
          secondData.complete(list);
        }
      }
    });
    addTearDown(sub.close);
    await carrier.link!.jobOpened;
    carrier.link!.pushJob('s1', [job('j1')]);
    final first = await target.future.timeout(const Duration(seconds: 1));
    expect(first.where((job) => job.id == 'j1' && job.status == 'running'), isNotEmpty);
    expect(
      (await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.ssh('remote'), 's1')),
      isNull,
    );
    expect(carrier.contributionRequestsStarted, 1);

    carrier.releaseContribution(0, 'host-1');
    final reconciled = await secondData.future.timeout(const Duration(seconds: 1));
    expect(reconciled.where((job) => job.id == 'j1' && job.status == 'running'), isNotEmpty);
    final stored = jsonDecode(
      (await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.ssh('remote'), 's1'))!,
    );
    expect(stored['hostInstanceId'], 'host-1');
    expect(stored['jobs'].keys, ['j1']);
  });

  test('save after current scope expires is skipped', () async {
    final key = JobObservationStore.keyFor(HostRef.ssh('remote'), 's1');
    var current = true;
    final store = JobObservationStore(key, isCurrent: () => current);
    await store.load();
    final observations = JobObservations(
      hostInstanceId: 'host-1',
      jobs: {'j1': ObservedJob.fromJob(job('j1'))},
      loaded: true,
    );
    final saveFuture = store.save(observations);
    current = false;
    await saveFuture;
    expect((await SharedPreferences.getInstance()).getString(key), isNull);

    current = true;
    await store.save(observations);
    expect(jsonDecode((await SharedPreferences.getInstance()).getString(key)!), isNotNull);
  });

  test('a late old-generation HTTP identity does not override the next generation result', () async {
    final carrier = FakePluginCarrier(gateContributions: true);
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final container = providerContainer(client);
    addTearDown(container.dispose);
    await client.mux.ready();
    final nextData = Completer<List<JobInfo>>();
    final sub = container.listen(jobsProvider('s1'), (_, state) {
      if (state.value case final list? when list.any((job) => job.id == 'j2')) {
        if (!nextData.isCompleted) nextData.complete(list);
      }
    });
    addTearDown(sub.close);
    await carrier.link!.jobOpened;
    final oldLink = carrier.link!;
    oldLink.pushJob('s1', [job('j1')]);
    expect(carrier.contributionRequestsStarted, 1);

    final oldGeneration = client.mux.generation;
    final newGenerationFuture = client.mux.generations.firstWhere((g) => !identical(g, oldGeneration));
    await oldLink.close(4000, 'lost');
    final newGeneration = await newGenerationFuture.timeout(const Duration(seconds: 1));
    expect(newGeneration.number, 2);
    final newLink = carrier.link!;
    await newLink.jobOpened;
    newLink.pushJob('s1', [job('j2')]);
    expect(carrier.contributionRequestsStarted, 2);
    carrier.releaseContribution(1, 'host-2');
    final next = await nextData.future.timeout(const Duration(seconds: 1));
    expect(next.where((job) => job.id == 'j2' && job.status == 'running'), isNotEmpty);
    final storedAfterNew = jsonDecode(
      (await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.ssh('remote'), 's1'))!,
    );
    expect(storedAfterNew['hostInstanceId'], 'host-2');
    expect(storedAfterNew['jobs'].keys, ['j2']);

    carrier.releaseContribution(0, 'host-1');
    oldLink.pushJob('s1', [job('j1', status: 'running')]);
    await Future<void>(() {});
    final storedAfterLate = jsonDecode(
      (await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.ssh('remote'), 's1'))!,
    );
    expect(storedAfterLate['hostInstanceId'], 'host-2');
    expect(storedAfterLate['jobs'].keys, ['j2']);
  });

  test('dispose cancels the job stream before late HTTP or frames write again', () async {
    final carrier = FakePluginCarrier(gateContributions: true);
    final client = DshClient.withCarrier(carrier, muxOptions: const MuxOptions(retryCaps: [Duration(milliseconds: 1)]));
    addTearDown(client.close);
    final container = providerContainer(client);
    await client.mux.ready();
    final events = <List<JobInfo>>[];
    final firstData = Completer<List<JobInfo>>();
    final sub = container.listen(jobsProvider('s1'), (_, state) {
      if (state.value case final list?) {
        events.add(list);
        if (!firstData.isCompleted) firstData.complete(list);
      }
    });
    addTearDown(sub.close);
    final link = carrier.link!;
    await link.jobOpened;
    link.pushJob('s1', [job('j1')]);
    final first = await firstData.future.timeout(const Duration(seconds: 1));
    expect(first.where((job) => job.id == 'j1' && job.status == 'running'), isNotEmpty);
    expect(carrier.contributionRequestsStarted, 1);

    container.dispose();
    await link.jobCancelled.timeout(const Duration(seconds: 1));
    carrier.releaseContribution(0, 'host-1');
    link.pushJob('s1', [job('j2')]);
    await Future<void>(() {});
    expect(events, hasLength(1));
    expect(
      (await SharedPreferences.getInstance()).getString(JobObservationStore.keyFor(HostRef.ssh('remote'), 's1')),
      isNull,
    );
  });
}
