import 'dart:async';
import 'dart:io';

import 'package:dsh_app/state/artifact_files.dart';
import 'package:dsh_app/state/artifacts.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _artifact = Artifact(path: '/home/me/a.png', origin: ArtifactOrigin.linked, turn: 1);

class _Carrier extends DshCarrier {
  String version = 'v1';
  String absolutePath = _artifact.path;
  bool failStat = false;
  int statCalls = 0;
  int? size = 4;
  final requests = <Completer<RpcResult>>[];
  final entered = List.generate(8, (_) => Completer<void>());

  @override
  String get kind => 'memory';
  @override
  DshTracer? get tracer => null;

  @override
  Future<RpcResult> call(String endpoint, Map<String, Object?> args, {Duration? timeout}) async {
    if (endpoint == 'workspaceFiles/stat') {
      statCalls++;
      if (failStat) throw const DshTransportException('offline');
      return RpcOk({'absolutePath': absolutePath, 'version': version, 'bytes': size});
    }
    if (endpoint == 'workspaceFiles/readBytes') {
      final request = Completer<RpcResult>();
      requests.add(request);
      entered[requests.length - 1].complete();
      return request.future;
    }
    return const RpcError(DshRemoteException('gateway/not-found', 'missing'));
  }

  void succeed(int index, {List<int> bytes = const [1, 2, 3, 4], String responseVersion = 'v1'}) {
    requests[index].complete(
      RpcOk({
        'absolutePath': _artifact.path,
        'version': responseVersion,
        'bytes': bytes.length,
        'offset': 0,
        'data': bytes,
        'eof': true,
      }),
    );
  }

  void fail(int index) {
    requests[index].complete(const RpcError(DshRemoteException('workspace-file/not-allowed', 'synthetic failure')));
  }

  @override
  Future<DshMuxLink> openLink(MuxOptions options) => throw UnsupportedError('unused');
  @override
  Future<void> close() async {}
}

class _Rig {
  _Rig(this.cache);
  final Directory cache;
  final carrier = _Carrier();
  late final client = DshClient.withCarrier(carrier);
  late final files = forHost('host-a');

  ArtifactFiles forHost(String host) => ArtifactFiles(
    connection: HostConnection(client: client, label: host, isLocal: false, hostId: host),
    channel: const FilesChannel(),
    cacheRoot: () async => cache,
  );

  ArtifactFiles forLocal({String hostId = 'local'}) => ArtifactFiles(
    connection: HostConnection(client: client, label: hostId, isLocal: true, hostId: hostId),
    channel: const FilesChannel(),
    cacheRoot: () async => cache,
  );

  int resolveCalls = 0;
  Completer<void>? resolverEntered;
  Completer<void>? resolverRelease;
  List<int> localBytes = [8, 8, 8, 8];
  String? nativePathOverride;

  Future<void> installNativeResolver() async {
    const channel = MethodChannel('dsh/files');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method != 'resolveGuest') return null;
      resolveCalls++;
      if (nativePathOverride case final path?) return path;
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

  test('ordinary native paths are returned without moving or caching', () async {
    await rig.installNativeResolver();
    final ordinary = File('${rig.cache.path}/ordinary.png');
    await ordinary.writeAsBytes([4, 4, 4, 4]);
    rig.nativePathOverride = ordinary.path;

    final resolved = await rig.forLocal().materialize('s1', _artifact);
    expect(resolved.path, ordinary.path);
    expect(await resolved.readAsBytes(), [4, 4, 4, 4]);
    expect(await Directory('${rig.cache.path}/artifacts').exists(), isFalse);
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

  test('local stat failure preserves native resolution and does not trust old versions', () async {
    await rig.installNativeResolver();
    final local = rig.forLocal();
    final cached = await local.materialize('s1', _artifact);
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
}
