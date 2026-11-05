import 'dart:async';
import 'dart:convert';

import 'package:dsh_client/dsh_client.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'hosts_state.dart';

/// One job observed while a Host process was running. Output and process
/// handles stay in the Host; this is only enough to label a later different
/// instance as a restart.
class ObservedJob {
  const ObservedJob({
    required this.id,
    required this.kind,
    required this.label,
    required this.startedAt,
    this.progress,
  });

  factory ObservedJob.fromJob(JobInfo job) => ObservedJob(
    id: job.id,
    kind: job.kind,
    label: job.label,
    startedAt: job.startedAt,
    progress: job.progress,
  );

  static ObservedJob? fromJson(Object? json) {
    final map = json is Map ? json : const {};
    final id = map['id'];
    if (id is! String || id.isEmpty) return null;
    return ObservedJob(
      id: id,
      kind: map['kind'] is String ? map['kind'] as String : 'bash',
      label: map['label'] is String ? map['label'] as String : id,
      startedAt: switch (map['startedAt']) {
        final int value => DateTime.fromMillisecondsSinceEpoch(value),
        _ => null,
      },
      progress: map['progress'] is String ? map['progress'] as String : null,
    );
  }

  final String id;
  final String kind;
  final String label;
  final DateTime? startedAt;
  final String? progress;

  @override
  bool operator ==(Object other) =>
      other is ObservedJob &&
      id == other.id &&
      kind == other.kind &&
      label == other.label &&
      startedAt == other.startedAt &&
      progress == other.progress;

  @override
  int get hashCode => Object.hash(id, kind, label, startedAt, progress);

  Map<String, Object?> toJson() => {
    'id': id,
    'kind': kind,
    'label': label,
    'startedAt': ?startedAt?.millisecondsSinceEpoch,
    'progress': ?progress,
  };
}

/// The last known live jobs and the Host instance that owned them.
class JobObservations {
  const JobObservations({
    this.hostInstanceId,
    this.jobs = const {},
    this.interrupted = const {},
    this.loaded = false,
  });

  factory JobObservations.fromJson(Object? json) {
    final map = json is Map ? json : const {};
    final jobs = map['jobs'];
    return JobObservations(
      hostInstanceId: map['hostInstanceId'] is String ? map['hostInstanceId'] as String : null,
      jobs: {
        if (jobs is Map)
          for (final MapEntry(:key, :value) in jobs.entries)
            if (key is String) key: ?ObservedJob.fromJson(value),
      },
      interrupted: {
        if (map['interrupted'] is Map)
          for (final MapEntry(:key, :value) in (map['interrupted'] as Map).entries)
            if (key is String)
              key: ?InterruptedJob.fromJson(value),
      },
      loaded: true,
    );
  }

  final String? hostInstanceId;
  final Map<String, ObservedJob> jobs;
  final Map<String, InterruptedJob> interrupted;
  final bool loaded;

  JobObservations withJobs(String? instanceId, Iterable<JobInfo> jobs) => JobObservations(
    hostInstanceId: instanceId,
    jobs: {for (final job in jobs.where((job) => job.live)) job.id: ObservedJob.fromJob(job)},
    interrupted: interrupted,
    loaded: true,
  );

  JobObservations withoutJob(String jobId) => JobObservations(
    hostInstanceId: hostInstanceId,
    jobs: {...jobs}..remove(jobId),
    interrupted: interrupted,
    loaded: true,
  );

  JobObservations withoutInterruptedJob(String jobId) => JobObservations(
    hostInstanceId: hostInstanceId,
    jobs: jobs,
    interrupted: {...interrupted}..remove(jobId),
    loaded: true,
  );

  Map<String, Object?> toJson() => {
    'hostInstanceId': ?hostInstanceId,
    'jobs': {for (final MapEntry(:key, :value) in jobs.entries) key: value.toJson()},
    'interrupted': {for (final MapEntry(:key, :value) in interrupted.entries) key: value.toJson()},
  };
}

/// A job that ran under a previous, different Host instance.
class InterruptedJob {
  const InterruptedJob(this.observation, this.interruptedAt);

  static InterruptedJob? fromJson(Object? json) {
    final map = json is Map ? json : const {};
    final observation = ObservedJob.fromJson(map['observation']);
    final interruptedAt = switch (map['interruptedAt']) {
      final int value => DateTime.fromMillisecondsSinceEpoch(value),
      _ => null,
    };
    return observation == null || interruptedAt == null ? null : InterruptedJob(observation, interruptedAt);
  }

  final ObservedJob observation;
  final DateTime interruptedAt;

  Map<String, Object?> toJson() => {
    'observation': observation.toJson(),
    'interruptedAt': interruptedAt.millisecondsSinceEpoch,
  };

  JobInfo toJobInfo() => JobInfo(
    id: observation.id,
    kind: observation.kind,
    label: observation.label,
    status: 'interrupted',
    progress: observation.progress,
    startedAt: observation.startedAt,
    finishedAt: interruptedAt,
  );
}

/// Read/write the observation metadata for one logical host and session.
class JobObservationStore {
  JobObservationStore(this._key, {this.isCurrent});

  static String keyFor(HostRef host, String sessionId) =>
      'job.observations.${host.kind.name}.${host.id}.$sessionId';

  final String _key;
  final bool Function()? isCurrent;

  Future<JobObservations> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_key);
      return raw == null ? const JobObservations() : JobObservations.fromJson(jsonDecode(raw));
    } catch (_) {
      return const JobObservations(loaded: true);
    }
  }

  Future<void> save(JobObservations observations) async {
    if (!observations.loaded) return;
    try {
      final prefs = await SharedPreferences.getInstance();
      if (isCurrent?.call() == false) return;
      await prefs.setString(_key, jsonEncode(observations.toJson()));
    } catch (_) {
      // Keep the current run's observations in memory only.
    }
  }
}

/// Watches process-local jobs across ready generations. A lost link waits for
/// the Host's next ready set instead of preserving its stale running rows.

/// Reconciles process-local jobs against the last observed Host instance.
class JobWatchController {
  JobWatchController(this.store, {this.isCurrent});

  final JobObservationStore store;
  final bool Function()? isCurrent;
  JobObservations _observations = const JobObservations();
  Map<String, InterruptedJob> _interrupted = {};
  String? _liveInstance;

  Map<String, InterruptedJob> get interrupted => Map.unmodifiable(_interrupted);

  Future<void> load() async {
    _observations = await store.load();
    _liveInstance = _observations.hostInstanceId;
    _interrupted = {..._observations.interrupted};
  }

  Future<List<JobInfo>> reconcile(
    MuxGeneration? generation,
    List<JobInfo> jobs, {
    DateTime Function() now = DateTime.now,
  }) async {
    final instance = generation?.hostInstanceId;
    if (isCurrent?.call() == false) return [];
    if (instance == null) {
      return [...jobs, ..._interrupted.values.map((job) => job.toJobInfo())];
    }
    final previous = _liveInstance;
    if (previous != null && previous != instance) {
      _observations = JobObservations(
        hostInstanceId: _observations.hostInstanceId,
        jobs: _observations.jobs,
        interrupted: {
          ..._observations.interrupted,
          for (final job in _observations.jobs.values) job.id: InterruptedJob(job, now()),
        },
      );
      _liveInstance = instance;
      _observations = _observations.withJobs(instance, jobs);
      _interrupted = {..._observations.interrupted};
      await store.save(_observations);
      if (isCurrent?.call() == false) return [];
      return [...jobs, ..._interrupted.values.map((job) => job.toJobInfo())];
    }
    if (previous == null) _liveInstance = instance;
    final live = {for (final job in jobs.where((job) => job.live)) job.id: ObservedJob.fromJob(job)};
    var unchanged = _observations.jobs.length == live.length &&
        _observations.jobs.entries.every((entry) => live[entry.key] == entry.value);
    if (previous == instance && unchanged) return [...jobs, ..._interrupted.values.map((job) => job.toJobInfo())];
    _observations = _observations.withJobs(instance, jobs);
    await store.save(_observations);
    if (isCurrent?.call() == false) return [];
    return [...jobs, ..._interrupted.values.map((job) => job.toJobInfo())];
  }

  Future<void> jobEnded(String jobId) async {
    if (_observations.jobs.containsKey(jobId)) {
      _observations = _observations.withoutJob(jobId);
      await store.save(_observations);
      return;
    }
    if (!_observations.interrupted.containsKey(jobId)) return;
    _observations = _observations.withoutInterruptedJob(jobId);
    await store.save(_observations);
  }
}
