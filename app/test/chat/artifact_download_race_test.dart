import 'dart:async';
import 'dart:io';

import 'package:dsh_app/state/artifact_files.dart';
import 'package:dsh_app/state/artifacts.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/state/hosts_state.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/painting.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _artifact = Artifact(path: '/home/me/a.png', origin: ArtifactOrigin.linked, turn: 1);

class _FixedLocalHost extends ActiveHostController {
  @override
  HostRef build() => HostRef.local;
}

class _RuntimeBuildProbe extends LocalRuntimeController {
  int buildCalls = 0;

  @override
  LocalRuntimeState build() {
    buildCalls++;
    return const LocalRuntimeState(loaded: true);
  }
}

class _Carrier extends DshCarrier {
  String version = 'v1';
  String absolutePath = _artifact.path;
  bool failStat = false;
  DshRemoteException? statFailure;
  int? statHttpFailure;
  bool immediateRead = false;
  DshRemoteException? readFailure;
  bool allowReadEndpoint = true;
  int readCalls = 0;
  int statCalls = 0;
  List<int> apiBytes = [6, 6, 6, 6];
  int? size = 4;
  final requests = <Completer<RpcResult>>[];
  final requestSessions = <String>[];
  String? drainSession;
  int closeCalls = 0;
  void Function(int index, String session)? onRead;
  final entered = List.generate(8, (_) => Completer<void>());

  @override
  String get kind => 'memory';
  @override
  DshTracer? get tracer => null;

  @override
  bool allowsEndpoint(String endpoint) => endpoint != 'workspaceFiles/readBytes' || allowReadEndpoint;

  @override
  Future<RpcResult> call(String endpoint, Map<String, Object?> args, {Duration? timeout}) async {
    if (endpoint == 'workspaceFiles/stat') {
      statCalls++;
      if (failStat) throw const DshTransportException('offline');
      if (statHttpFailure case final status?) throw DshHttpException(status, 'endpoint unavailable');
      if (statFailure case final failure?) return RpcError(failure);
      return RpcOk({'absolutePath': absolutePath, 'version': version, 'bytes': size});
    }
    if (endpoint == 'workspaceFiles/readBytes') {
      readCalls++;
      if (readFailure case final failure?) return RpcError(failure);
      if (immediateRead) {
        final options = args['options'] as Map;
        final range = options['range'] as Map;
        final offset = range['offset'] as int;
        final data = offset >= apiBytes.length ? <int>[] : apiBytes.sublist(offset);
        return RpcOk({'data': data, 'bytes': apiBytes.length, 'eof': true});
      }
      final request = Completer<RpcResult>();
      requests.add(request);
      final session = args['workspaceFileScopeId'] as String;
      requestSessions.add(session);
      entered[requests.length - 1].complete();
      if (closeCalls > 0) {
        request.completeError(const DshClosedException('carrier closed'));
      } else if (session == drainSession) {
        request.complete(const RpcOk({'data': <int>[], 'eof': true}));
      }
      onRead?.call(requests.length - 1, session);
      return request.future;
    }
    return const RpcError(DshRemoteException('gateway/not-found', 'missing'));
  }

  void succeed(int index, {List<int> bytes = const [1, 2, 3, 4], String responseVersion = 'v1', bool eof = true}) {
    requests[index].complete(
      RpcOk({
        'absolutePath': _artifact.path,
        'version': responseVersion,
        'bytes': bytes.length,
        'offset': 0,
        'data': bytes,
        'eof': eof,
      }),
    );
  }

  void fail(int index) {
    requests[index].complete(const RpcError(DshRemoteException('workspace-file/not-allowed', 'synthetic failure')));
  }

  @override
  Future<DshMuxLink> openLink(MuxOptions options) => throw UnsupportedError('unused');
  @override
  Future<void> close() async {
    closeCalls++;
  }
}

class _Rig {
  _Rig(this.cache);
  final Directory cache;
  final carrier = _Carrier();
  late final client = DshClient.withCarrier(carrier);
  late final files = forHost('host-a');

  ArtifactFiles forHost(String host) => ArtifactFiles(
    connection: HostConnection(client: client, label: host, isLocal: false, kind: HostKind.ssh, hostId: host),
    channel: const FilesChannel(),
    selectedHost: HostRef.ssh(host),
    cacheRoot: () async => cache,
  );

  ArtifactFiles forLocal({String hostId = 'local', bool rootRuntime = false}) => ArtifactFiles(
    connection: HostConnection(client: client, label: hostId, isLocal: true, hostId: hostId),
    channel: const FilesChannel(),
    selectedHost: HostRef.local,
    rootRuntime: rootRuntime,
    cacheRoot: () async => cache,
  );

  ArtifactFiles forOfflineLocal() => ArtifactFiles(
    connection: null,
    channel: const FilesChannel(),
    selectedHost: HostRef.local,
    rootRuntime: true,
    cacheRoot: () async => cache,
  );

  int resolveCalls = 0;
  Completer<void>? resolverEntered;
  Completer<void>? resolverRelease;
  List<int> localBytes = [8, 8, 8, 8];
  String? nativePathOverride;
  bool nativeMissing = false;

  Future<void> installNativeResolver() async {
    const channel = MethodChannel('dsh/files');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method != 'resolveGuest') return null;
      resolveCalls++;
      if (nativePathOverride case final path?) return path;
      if (nativeMissing) return null;
      final callIndex = resolveCalls;
      if (resolverEntered != null && callIndex == 2) resolverEntered!.complete();
      if (resolverRelease != null) await resolverRelease!.future;
      final directory = Directory('${cache.path}/artifacts');
      await directory.create(recursive: true);
      final file = File('${directory.path}/guest-artifact-$callIndex.tmp');
      await file.writeAsBytes(localBytes);
      return file.path;
    });
  }

  Future<int> cachedFileCount() async => cache.list(recursive: true).where((entry) => entry is File).length;

  Future<void> noStagingLeft() async {
    final staging = await cache
        .list(recursive: true)
        .where((entry) => entry.path.split('/').last.startsWith('.part-'))
        .toList();
    expect(staging, isEmpty);
  }
}

Future<Object> _observed(Future<Object> future) =>
    future.then<Object>((value) => value, onError: (Object error, StackTrace _) => error);

const _noSpace = FileSystemException('write failed', '', OSError('No space left', 28));

class _FailingConsumer implements StreamConsumer<List<int>> {
  @override
  Future<void> addStream(Stream<List<int>> stream) async {
    await for (final _ in stream) {
      throw _noSpace;
    }
  }

  @override
  Future<void> close() async {}
}

class _FailingHandle implements RandomAccessFile {
  _FailingHandle(this.file);
  final RandomAccessFile file;
  @override
  Future<RandomAccessFile> writeFrom(List<int> buffer, [int start = 0, int? end]) async => throw _noSpace;
  @override
  Future<void> close() => file.close();
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FaultFile implements File {
  _FaultFile(this.file, {required this.openFailure, required this.makeFile});
  final File file;
  final bool openFailure;
  final File Function(String) makeFile;
  File get missing => makeFile('${file.path}.missing-parent/clip');
  @override
  String get path => file.path;
  @override
  Directory get parent => file.parent;
  @override
  Future<RandomAccessFile> open({FileMode mode = FileMode.read}) async =>
      openFailure ? await missing.open(mode: mode) : _FailingHandle(await file.open(mode: mode));
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Rig rig;
  setUp(() async {
    rig = _Rig(await Directory.systemTemp.createTemp('artifact-download-test-'));
  });
  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('dsh/files'),
      null,
    );
    await rig.client.close();
    await rig.cache.delete(recursive: true);
  });

  test('selected local host resolves natively without a DSH connection', () async {
    await rig.installNativeResolver();
    final ordinary = File('${rig.cache.path}/offline.png');
    await ordinary.writeAsBytes([5, 5, 5, 5]);
    rig.nativePathOverride = ordinary.path;
    final local = rig.forOfflineLocal();

    expect(local.isLocal, isTrue);
    expect(local.canFetch, isTrue);
    final resolved = await local.materialize('s1', _artifact);
    expect(resolved.path, ordinary.path);
    expect(rig.carrier.statCalls, 0);
    expect(rig.carrier.readCalls, 0);
  });

  test('the provider factory resolves a local Native file without runtime events', () async {
    const channel = MethodChannel('test/dsh/files-provider');
    final ordinary = File('${rig.cache.path}/provider-native.png');
    await ordinary.writeAsBytes([5, 5, 5, 5]);
    var resolveCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method != 'resolveGuest') return null;
      resolveCalls++;
      return ordinary.path;
    });
    final runtimeProbe = _RuntimeBuildProbe();
    final container = ProviderContainer(
      overrides: [
        activeHostProvider.overrideWith(_FixedLocalHost.new),
        activeConnectionProvider.overrideWithValue(const AsyncData<HostConnection?>(null)),
        filesChannelProvider.overrideWithValue(FilesChannel(channel)),
        localRuntimeProvider.overrideWith(() => runtimeProbe),
      ],
    );
    try {
      final files = container.read(artifactFilesProvider);
      expect(files.isLocal, isTrue);
      expect(files.canFetch, isTrue);
      final resolved = await files.materialize('s1', _artifact);
      expect(resolved.path, ordinary.path);
      expect(await resolved.readAsBytes(), [5, 5, 5, 5]);
      expect(resolveCalls, 1);
      expect(runtimeProbe.buildCalls, 0);
    } finally {
      container.dispose();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    }
  });

  test('a disconnected remote host does not resolve through the local channel', () async {
    await rig.installNativeResolver();
    final remote = ArtifactFiles(
      connection: null,
      selectedHost: const HostRef.ssh('host-a'),
      channel: const FilesChannel(),
      cacheRoot: () async => rig.cache,
    );

    expect(remote.canFetch, isFalse);
    await expectLater(
      remote.materialize('s1', _artifact),
      throwsA(isA<ArtifactError>().having((error) => error.code, 'code', 'offline')),
    );
    expect(rig.resolveCalls, 0);
  });

  test('a removed remote host cannot use the temporary local connection', () async {
    await rig.installNativeResolver();
    final underlay = File('${rig.cache.path}/local-only.png');
    await underlay.writeAsBytes([1, 1, 1, 1]);
    rig.nativePathOverride = underlay.path;
    rig.carrier.immediateRead = true;
    rig.carrier.apiBytes = [9, 9, 9, 9];
    final transitional = ArtifactFiles(
      connection: HostConnection(client: rig.client, label: 'This phone', isLocal: true),
      selectedHost: const HostRef.ssh('host-a'),
      rootRuntime: true,
      channel: const FilesChannel(),
      cacheRoot: () async => rig.cache,
    );

    expect(transitional.isLocal, isFalse);
    expect(transitional.canFetch, isFalse);
    await expectLater(
      transitional.materialize('s1', _artifact),
      throwsA(isA<ArtifactError>().having((error) => error.code, 'code', 'offline')),
    );
    expect(rig.resolveCalls, 0);
    expect(rig.carrier.statCalls, 0);
    expect(rig.carrier.readCalls, 0);
  });

  test('local Root export is reused until its stat version changes', () async {
    await rig.installNativeResolver();
    final local = rig.forLocal();
    final first = await local.materialize('s1', _artifact);
    for (var index = 0; index < 9; index++) {
      expect((await local.materialize('s1', _artifact)).path, first.path);
    }
    expect(rig.resolveCalls, 1);
    expect(await first.readAsBytes(), [8, 8, 8, 8]);
    expect(await rig.cachedFileCount(), 1);

    rig.carrier.version = 'v2';
    rig.localBytes = [2, 2, 2, 2];
    final refreshed = await local.materialize('s1', _artifact);
    expect(refreshed.path, isNot(first.path));
    expect(await refreshed.readAsBytes(), [2, 2, 2, 2]);
    expect(rig.resolveCalls, 2);
    expect(await rig.cachedFileCount(), 2);
    await rig.noStagingLeft();
  });

  test('a bind-covered target exports guest bytes instead of rootfs underlay bytes', () async {
    await rig.installNativeResolver();
    final underlay = File('${rig.cache.path}/underlay.txt');
    await underlay.writeAsBytes([1, 1, 1, 1]);
    rig.nativePathOverride = underlay.path;
    rig.carrier.immediateRead = true;
    rig.carrier.apiBytes = [9, 9, 9, 9];

    final local = rig.forLocal(rootRuntime: true);
    final resolved = await local.materialize('s1', _artifact);
    expect(await resolved.readAsBytes(), [9, 9, 9, 9]);
    final cached = await local.materialize('s1', _artifact);
    expect(cached.path, resolved.path);
    expect(rig.carrier.readCalls, 1);

    rig.carrier.version = 'v2';
    rig.carrier.apiBytes = [2, 2, 2, 2];
    final refreshed = await local.materialize('s1', _artifact);
    expect(refreshed.path, resolved.path);
    expect(await refreshed.readAsBytes(), [2, 2, 2, 2]);
    expect(rig.carrier.readCalls, 2);
    expect(rig.resolveCalls, 0);
    await rig.noStagingLeft();
  });

  test('mount-only bytes fall back to DSH, reuse one version, and refresh', () async {
    await rig.installNativeResolver();
    rig.nativeMissing = true;
    rig.carrier.immediateRead = true;
    rig.carrier.apiBytes = [7, 7, 7, 7];
    final local = rig.forLocal();

    final first = await local.materialize('s1', _artifact);
    expect(await first.readAsBytes(), [7, 7, 7, 7]);
    final cached = await local.materialize('s1', _artifact);
    expect(cached.path, first.path);
    expect(rig.carrier.readCalls, 1);

    rig.carrier.version = 'v2';
    rig.carrier.apiBytes = [2, 2, 2, 2];
    final refreshed = await local.materialize('s1', _artifact);
    expect(await refreshed.readAsBytes(), [2, 2, 2, 2]);
    expect(refreshed.path, first.path);
    expect(rig.carrier.readCalls, 2);
    expect(rig.resolveCalls, 3);
    await rig.noStagingLeft();
  });

  test('overlapping Root API reads publish one versioned file without staging leaks', () async {
    await rig.installNativeResolver();
    rig.nativeMissing = true;
    final local = rig.forLocal(rootRuntime: true);
    final first = local.materialize('s1', _artifact);
    await rig.carrier.entered[0].future;
    final second = local.materialize('s1', _artifact);
    await rig.carrier.entered[1].future;
    rig.carrier.succeed(0, bytes: [9, 9, 9, 9]);
    rig.carrier.succeed(1, bytes: [9, 9, 9, 9]);

    final files = await Future.wait([first, second]);
    expect(files[0].path, files[1].path);
    expect(await files[0].readAsBytes(), [9, 9, 9, 9]);
    expect(rig.carrier.requests.length, 2);
    expect(rig.resolveCalls, 0);
    await rig.noStagingLeft();
  });

  test('ordinary native paths are returned without moving or caching', () async {
    await rig.installNativeResolver();
    final ordinary = File('${rig.cache.path}/ordinary.png');
    await ordinary.writeAsBytes([4, 4, 4, 4]);
    rig.nativePathOverride = ordinary.path;

    final resolved = await rig.forLocal().materialize('s1', _artifact);
    expect(resolved.path, ordinary.path);
    expect(await resolved.readAsBytes(), [4, 4, 4, 4]);
    expect(await Directory('${rig.cache.path}/artifacts').exists(), isFalse);
    expect(rig.carrier.readCalls, 0);
    expect(rig.resolveCalls, 1);
  });

  test('local cache separates host, session, and resolved path sources', () async {
    await rig.installNativeResolver();
    final first = await rig.forLocal(hostId: 'host-a').materialize('s1', _artifact);
    final otherHost = await rig.forLocal(hostId: 'host-b').materialize('s1', _artifact);
    final otherSession = await rig.forLocal(hostId: 'host-a').materialize('s2', _artifact);
    rig.carrier.absolutePath = '/home/me/other.png';
    final otherPath = await rig.forLocal(hostId: 'host-a').materialize('s1', _artifact);
    expect({first.path, otherHost.path, otherSession.path, otherPath.path}.length, 4);
    expect(await rig.cachedFileCount(), 4);
    expect(rig.resolveCalls, 4);
  });

  test('overlapping local resolves publish one target and clean native staging', () async {
    await rig.installNativeResolver();
    rig.resolverEntered = Completer<void>();
    rig.resolverRelease = Completer<void>();
    final local = rig.forLocal();
    final first = local.materialize('s1', _artifact);
    final second = local.materialize('s1', _artifact);
    await rig.resolverEntered!.future;
    rig.resolverRelease!.complete();
    final files = await Future.wait([first, second]);
    expect(files[0].path, files[1].path);
    expect(await files[0].readAsBytes(), [8, 8, 8, 8]);
    expect(rig.resolveCalls, 2);
    expect(await rig.cachedFileCount(), 1);
    await rig.noStagingLeft();
  });

  test('failed cache publication removes the native Root temp file', () async {
    await rig.installNativeResolver();
    final local = rig.forLocal();
    final cached = await local.materialize('s1', _artifact);
    final targetDirectory = cached.parent;
    await targetDirectory.delete(recursive: true);
    await File(targetDirectory.path).writeAsString('blocked');

    await expectLater(local.materialize('s1', _artifact), throwsA(isA<FileSystemException>()));
    final remaining = await Directory('${rig.cache.path}/artifacts').list().toList();
    expect(remaining.where((entry) => entry.path.split('/').last.startsWith('guest-artifact-')), isEmpty);
  });

  test('native missing with stat not-found preserves the missing error', () async {
    await rig.installNativeResolver();
    rig.nativeMissing = true;
    rig.carrier.statFailure = const DshRemoteException('workspace-file/not-found', 'missing');
    await expectLater(
      rig.forLocal(rootRuntime: true).materialize('s1', _artifact),
      throwsA(isA<DshRemoteException>().having((error) => error.code, 'code', 'workspace-file/not-found')),
    );
    expect(rig.carrier.readCalls, 0);
    expect(rig.resolveCalls, 0);
  });

  test('native missing without a read endpoint remains unsupported', () async {
    await rig.installNativeResolver();
    rig.nativeMissing = true;
    rig.carrier.allowReadEndpoint = false;
    await expectLater(
      rig.forLocal().materialize('s1', _artifact),
      throwsA(isA<ArtifactError>().having((error) => error.code, 'code', 'unsupported')),
    );
    expect(rig.carrier.readCalls, 0);
  });

  test('Root runtime uses Native when the byte endpoint is unavailable', () async {
    await rig.installNativeResolver();
    final ordinary = File('${rig.cache.path}/ordinary.png');
    await ordinary.writeAsBytes([4, 4, 4, 4]);
    rig.nativePathOverride = ordinary.path;
    rig.carrier.allowReadEndpoint = false;

    final resolved = await rig.forLocal(rootRuntime: true).materialize('s1', _artifact);
    expect(await resolved.readAsBytes(), [4, 4, 4, 4]);
    expect(rig.carrier.statCalls, 0);
    expect(rig.carrier.readCalls, 0);
    expect(rig.resolveCalls, 1);
  });

  test('Root runtime uses Native when the stat endpoint is absent', () async {
    await rig.installNativeResolver();
    final ordinary = File('${rig.cache.path}/ordinary.png');
    await ordinary.writeAsBytes([4, 4, 4, 4]);
    rig.nativePathOverride = ordinary.path;
    rig.carrier.statHttpFailure = 404;

    final resolved = await rig.forLocal(rootRuntime: true).materialize('s1', _artifact);
    expect(await resolved.readAsBytes(), [4, 4, 4, 4]);
    expect(rig.carrier.statCalls, 1);
    expect(rig.carrier.readCalls, 0);
    expect(rig.resolveCalls, 1);
  });

  test('native missing with an explicit API refusal preserves that error', () async {
    await rig.installNativeResolver();
    rig.nativeMissing = true;
    rig.carrier.immediateRead = true;
    rig.carrier.readFailure = const DshRemoteException('workspace-file/not-allowed', 'refused');
    await expectLater(
      rig.forLocal(rootRuntime: true).materialize('s1', _artifact),
      throwsA(isA<DshRemoteException>().having((error) => error.code, 'code', 'workspace-file/not-allowed')),
    );
    expect(rig.carrier.readCalls, 1);
    expect(rig.resolveCalls, 0);
  });

  test('local stat failure preserves native resolution and does not trust old versions', () async {
    await rig.installNativeResolver();
    final local = rig.forLocal(rootRuntime: true);
    final cached = await rig.forLocal().materialize('s1', _artifact);
    rig.carrier.failStat = true;
    rig.localBytes = [3, 3, 3, 3];
    final fallback = await local.materialize('s1', _artifact);
    expect(await cached.readAsBytes(), [8, 8, 8, 8]);
    expect(await fallback.readAsBytes(), [3, 3, 3, 3]);
    expect(fallback.path, isNot(cached.path));
    expect(rig.resolveCalls, 2);
  });

  for (final thumbnailFirst in [true, false]) {
    test('thumbnail and preview both finish, thumbnailFirst=$thumbnailFirst', () async {
      final progress = <double?>[];
      final thumbnail = rig.files.thumbnail('s1', _artifact.path);
      await rig.carrier.entered[0].future;
      final preview = rig.files.materialize('s1', _artifact, onProgress: progress.add);
      await rig.carrier.entered[1].future;
      // The real thumbnail entry and preview download overlap, without decoding an image.
      if (thumbnailFirst) {
        rig.carrier.succeed(0);
        await thumbnail;
        rig.carrier.succeed(1);
      } else {
        rig.carrier.succeed(1);
        await preview;
        rig.carrier.succeed(0);
      }
      final image = await thumbnail as FileImage;
      final file = await preview;
      expect(image.file.path, file.path);
      expect(await image.file.readAsBytes(), [1, 2, 3, 4]);
      expect(await file.readAsBytes(), [1, 2, 3, 4]);
      expect(progress.first, 0);
      expect(progress.last, 1);
      await rig.noStagingLeft();
    });
  }

  test('a failed download only removes its own staging, leaving the peer usable', () async {
    final failed = _observed(rig.files.materialize('s1', _artifact));
    await rig.carrier.entered[0].future;
    final peer = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[1].future;
    rig.carrier.fail(0);
    expect(await failed, isA<DshRemoteException>());
    rig.carrier.succeed(1);
    expect(await (await peer).readAsBytes(), [1, 2, 3, 4]);
    await rig.noStagingLeft();
  });

  test('failure can be retried, and a successful retry is reused from cache', () async {
    final failed = _observed(rig.files.materialize('s1', _artifact));
    await rig.carrier.entered[0].future;
    rig.carrier.fail(0);
    expect(await failed, isA<DshRemoteException>());
    await rig.noStagingLeft();
    final retry = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[1].future;
    rig.carrier.succeed(1);
    final file = await retry;
    final cached = await rig.files.materialize('s1', _artifact);
    expect(cached.path, file.path);
    expect(await cached.readAsBytes(), [1, 2, 3, 4]);
    expect(rig.carrier.requests.length, 2);
    await rig.noStagingLeft();
  });

  test('different Hosts keep separate cache files for the same session and path', () async {
    final first = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[0].future;
    final second = rig.forHost('host-b').materialize('s1', _artifact);
    await rig.carrier.entered[1].future;
    rig.carrier.succeed(0, bytes: [1, 1, 1, 1]);
    rig.carrier.succeed(1, bytes: [2, 2, 2, 2]);
    final a = await first;
    final b = await second;
    expect(a.path, isNot(b.path));
    expect(await a.readAsBytes(), [1, 1, 1, 1]);
    expect(await b.readAsBytes(), [2, 2, 2, 2]);
    await rig.noStagingLeft();
  });

  test('a stricter caller keeps its byte limit during overlap and after a cache hit', () async {
    rig.carrier.size = null;
    final strict = _observed(rig.files.materialize('s1', _artifact, maxBytes: 3));
    await rig.carrier.entered[0].future;
    final wider = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[1].future;
    rig.carrier.succeed(0);
    expect(await strict, isA<DshRemoteException>().having((e) => e.code, 'code', 'workspace-file/too-large'));
    rig.carrier.succeed(1);
    expect(await (await wider).readAsBytes(), [1, 2, 3, 4]);
    await expectLater(
      rig.files.materialize('s1', _artifact, maxBytes: 3),
      throwsA(isA<ArtifactError>().having((e) => e.code, 'code', 'workspace-file/too-large')),
    );
    expect(rig.carrier.requests.length, 2);
    await rig.noStagingLeft();
  });

  test('overlapping versions publish matching bytes and marker, then refresh as needed', () async {
    final old = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[0].future;
    rig.carrier.version = 'v2';
    final newer = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[1].future;
    rig.carrier.succeed(1, bytes: [2, 2, 2, 2], responseVersion: 'v2');
    await newer;
    rig.carrier.succeed(0, bytes: [1, 1, 1, 1]);
    final last = await old;
    expect(await last.readAsBytes(), [1, 1, 1, 1]);
    expect(await File('${last.parent.path}/.version').readAsString(), 'v1');
    final refresh = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[2].future;
    rig.carrier.succeed(2, bytes: [2, 2, 2, 2], responseVersion: 'v2');
    final current = await refresh;
    expect(await current.readAsBytes(), [2, 2, 2, 2]);
    expect(await File('${current.parent.path}/.version').readAsString(), 'v2');
    await rig.files.materialize('s1', _artifact);
    expect(rig.carrier.requests.length, 3);
    await rig.noStagingLeft();
  });
  for (final openFailure in [false, true]) {
    test('async ${openFailure ? 'open' : 'write'} failure stays in materialize and leaves its peer alive', () async {
      // The peer already owns a staging directory, and shares the real DshClient.
      final peer = _observed(rig.files.materialize('peer', _artifact));
      await rig.carrier.entered[0].future;
      final outer = Zone.current;
      File realFile(String path) => outer.run(() => File(path));
      final zoneErrors = <Object>[];
      final firstFailure = Completer<void>();
      final finished = Completer<Object>();
      void signal() {
        if (!firstFailure.isCompleted) firstFailure.complete();
      }

      runZonedGuarded(
        () => IOOverrides.runZoned(
          () async {
            try {
              finished.complete(await rig.files.materialize('s1', _artifact));
            } catch (error) {
              finished.complete(error);
            } finally {
              signal();
            }
          },
          createFile: (path) {
            final file = realFile(path);
            return path.split('/').any((part) => part.startsWith('.part-'))
                ? _FaultFile(file, openFailure: openFailure, makeFile: realFile)
                : file;
          },
        ),
        (error, _) {
          zoneErrors.add(error);
          signal();
        },
      );
      if (!openFailure) {
        await rig.carrier.entered[1].future;
        rig.carrier.succeed(1, bytes: [1, 2], eof: false);
      }
      await firstFailure.future;
      // Old code can be awaiting a second RPC after losing the sink error to the zone.
      // Settle every outstanding/future window deterministically before asserting.
      rig.carrier.drainSession = 's1';
      for (var i = 1; i < rig.carrier.requests.length; i++) {
        if (rig.carrier.requestSessions[i] == 's1' && !rig.carrier.requests[i].isCompleted) {
          rig.carrier.succeed(i, bytes: const []);
        }
      }
      final failure = await finished.future;
      final filesBeforePeer = await rig.cache.list(recursive: true).where((f) => f is File).toList();
      final stagesBeforePeer = await rig.cache
          .list(recursive: true)
          .where((f) => f is Directory && f.path.split('/').last.startsWith('.part-'))
          .toList();
      // The peer must issue a fresh window after the failure has fully settled.
      final peerWindow = Completer<int>();
      rig.carrier.onRead = (index, session) {
        if (session == 'peer' && !peerWindow.isCompleted) peerWindow.complete(index);
      };
      rig.carrier.succeed(0, bytes: [1, 2], eof: false);
      final nextPeerIndex = await Future.any<int?>([peerWindow.future, peer.then((_) => null)]);
      if (nextPeerIndex != null && !rig.carrier.requests[nextPeerIndex].isCompleted) {
        rig.carrier.succeed(nextPeerIndex, bytes: [3, 4]);
      }
      final successful = await peer;
      final bytes = successful is File ? await successful.readAsBytes() : null;
      await rig.noStagingLeft();
      expect(zoneErrors, isEmpty, reason: 'Every async file error belongs to the materialize Future');
      if (openFailure) {
        expect(
          failure,
          isA<FileSystemException>()
              .having((e) => e.osError?.errorCode, 'errno', 2)
              .having((e) => e.path, 'path', endsWith('.missing-parent/clip')),
        );
      } else {
        expect(failure, same(_noSpace), reason: 'close must not replace the original ENOSPC');
      }
      expect(
        rig.carrier.requestSessions.where((s) => s == 's1'),
        hasLength(openFailure ? 0 : 1),
        reason: 'All requests are recorded, including drain and after the failure Future',
      );
      expect(rig.carrier.closeCalls, 0, reason: 'A file failure must not close the shared client');
      expect(nextPeerIndex, isNotNull);
      expect(rig.carrier.requestSessions.where((s) => s == 'peer'), hasLength(2));
      expect(successful, isA<File>());
      expect(
        filesBeforePeer.where((f) => !f.path.contains('/.part-')),
        isEmpty,
        reason: 'No target or version marker is published by the failed download',
      );
      expect(stagesBeforePeer, hasLength(1), reason: 'Only the peer staging survives failure cleanup');
      expect(bytes, [1, 2, 3, 4]);
      expect(await File('${(successful as File).parent.path}/.version').readAsString(), 'v1');
    });
  }

  test('a real zero-byte eof publishes an empty file and matching marker', () async {
    rig.carrier.size = 0;
    final download = rig.files.materialize('s1', _artifact);
    await rig.carrier.entered[0].future;
    rig.carrier.succeed(0, bytes: const []);
    final file = await download;
    expect(await file.length(), 0);
    expect(await File('${file.parent.path}/.version').readAsString(), 'v1');
    expect(rig.carrier.requests, hasLength(1));
    await rig.noStagingLeft();
  });
}
