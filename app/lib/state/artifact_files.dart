// Getting a chat artifact onto the phone and handing it to Android (open,
// share, save to Downloads).

import 'dart:async';
import 'dart:io';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../l10n/l10n.dart';
import 'artifacts.dart';
import 'connection_state.dart';
import 'hosts_state.dart';

/// Largest file fetched from a remote Host for open / share / save.
const maxRemoteArtifactBytes = 300 * 1024 * 1024;

/// Largest remote image fetched just to draw a thumbnail.
const maxThumbnailBytes = 8 * 1024 * 1024;
const _rootRuntimeBuild = bool.fromEnvironment('DSH_ROOT_RUNTIME');

/// Why an artifact action could not run (beyond Host and platform errors).
class ArtifactError implements Exception {
  const ArtifactError(this.code);

  /// `missing`, `offline`, `unsupported`.
  final String code;

  @override
  String toString() => 'ArtifactError($code)';
}

/// A sentence for a failed artifact action.
String artifactErrorText(Object error) {
  final code = switch (error) {
    ArtifactError(:final code) => code,
    PlatformException(:final code) => code,
    DshRemoteException(:final code) => code,
    DshTransportException() || DshCarrierLostException() || DshClosedException() => 'offline',
    _ => '',
  };
  return switch (code) {
    'missing' || 'workspace-file/not-found' => appL10n.stateFileMissing,
    'no_app' => appL10n.stateFileNoApp,
    'permission' => appL10n.stateStoragePermission,
    'workspace-file/too-large' => appL10n.stateFileConnectionLimit,
    'unsupported' || 'workspace-file/unsupported' || 'workspace-file/not-allowed' => appL10n.stateFileReadUnsupported,
    'offline' => appL10n.commonEngineUnreachable,
    _ => appL10n.commonFailedTryAgain,
  };
}

/// Artifact files of the active connection.
///
/// The local engine's files are already on the phone (inside the Linux
/// environment, or in shared storage): Android gets them in place. A remote
/// Host's file is downloaded once into the app cache (`artifacts/`), keyed by
/// Host, session and path, and fetched again when the Host reports a new
/// version.
class ArtifactFiles {
  ArtifactFiles({
    required this.connection,
    required this.channel,
    this.selectedHost,
    this.rootRuntime = false,
    Future<void> Function(ShareParams params)? share,
    Future<Directory> Function()? cacheRoot,
  }) : _share = share ?? _systemShare,
       _cacheRoot = cacheRoot ?? getTemporaryDirectory;

  final HostConnection? connection;
  final HostRef? selectedHost;
  final bool rootRuntime;
  final FilesChannel channel;
  final Future<void> Function(ShareParams params) _share;
  final Future<Directory> Function() _cacheRoot;

  static Future<void> _systemShare(ShareParams params) async {
    await SharePlus.instance.share(params);
  }

  bool get isLocal => selectedHost?.isLocal ?? connection?.isLocal ?? false;

  bool get _connectionMatchesSelection {
    final conn = connection;
    final host = selectedHost;
    if (conn == null || host == null) return conn != null;
    if (host.kind == HostKind.local) return conn.isLocal && conn.kind == HostKind.local;
    return !conn.isLocal && conn.kind == host.kind && conn.hostId == host.id;
  }

  /// Whether open / share / save can get at the selected Host's file.
  bool get canFetch {
    final conn = connection;
    if (conn == null) return isLocal;
    if (!_connectionMatchesSelection) return false;
    return conn.isLocal || conn.client.canReadFileBytes;
  }

  /// Whether the Host can turn Office files into PDFs for a preview.
  bool get canConvertOffice =>
      _connectionMatchesSelection && (connection?.client.supportsEndpoint('officeToPdf/render') ?? false);

  /// A PDF of an Office file, converted by the Host (as the official Web
  /// client previews them) and kept in the cache until the file changes.
  Future<File> officePdf(String sessionId, Artifact artifact) async {
    final conn = connection;
    if (conn == null || !_connectionMatchesSelection) throw const ArtifactError('offline');
    if (!canConvertOffice) throw const ArtifactError('unsupported');
    final client = conn.client;
    final stat = await client.statFile(sessionId, artifact.path);
    final root = await _cacheRoot();
    final key = _fnv('pdf\n${conn.hostId ?? conn.label}\n$sessionId\n${artifact.path}');
    final dir = Directory('${root.path}/artifacts/$key');
    final target = File('${dir.path}/${_safeName(artifact.name)}.pdf');
    final marker = File('${dir.path}/.version');
    if (target.existsSync() && marker.existsSync() && marker.readAsStringSync() == stat.version) {
      return target;
    }
    final rendered = await client.renderOfficePdf(sessionId, artifact.path);
    if (rendered.pdf.isEmpty) throw const ArtifactError('unsupported');
    await dir.create(recursive: true);
    await target.writeAsBytes(rendered.pdf, flush: true);
    marker.writeAsStringSync(stat.version);
    return target;
  }

  Future<File> _resolveNative(String path) async {
    final host = await channel.resolveGuest(path);
    if (host == null) throw const ArtifactError('missing');
    return File(host);
  }

  /// An Android file holding [artifact]; [onProgress] gets 0–1 (null while
  /// the size is unknown) during a download.
  Future<File> materialize(
    String sessionId,
    Artifact artifact, {
    void Function(double? progress)? onProgress,
    int maxBytes = maxRemoteArtifactBytes,
  }) async {
    final conn = connection;
    if (conn == null) {
      if (!isLocal) throw const ArtifactError('offline');
      return _resolveNative(artifact.path);
    }
    if (!_connectionMatchesSelection) throw const ArtifactError('offline');
    if (conn.isLocal) {
      if (rootRuntime) {
        if (!conn.client.canReadFileBytes) return _resolveNative(artifact.path);
        try {
          final stat = await conn.client.statFile(sessionId, artifact.path);
          final sourcePath = stat.absolutePath.isEmpty ? artifact.path : stat.absolutePath;
          final key = _fnv('local-api\n${conn.hostId ?? 'local'}\n$sessionId\n$sourcePath');
          return await _downloadToCache(
            client: conn.client,
            sessionId: sessionId,
            path: artifact.path,
            artifact: artifact,
            stat: stat,
            cacheKey: key,
            maxBytes: maxBytes,
            cacheRoot: _cacheRoot,
            onProgress: onProgress,
            requireVersion: true,
          );
        } on DshTransportException {
          return _resolveNative(artifact.path);
        } on DshCarrierLostException {
          return _resolveNative(artifact.path);
        } on DshClosedException {
          return _resolveNative(artifact.path);
        } on DshHttpException catch (error) {
          if (error.statusCode == 404) return _resolveNative(artifact.path);
          rethrow;
        } on DshRemoteException catch (error) {
          if (error.code == 'METHOD_NOT_ALLOWED' || error.code == 'workspace-file/unsupported') {
            return _resolveNative(artifact.path);
          }
          rethrow;
        }
      }
      DshFileStat? stat;
      (Object, StackTrace)? statFailure;
      try {
        stat = await conn.client.statFile(sessionId, artifact.path);
      } catch (error, stack) {
        statFailure = (error, stack);
      }
      final cacheVersion = stat != null && stat.absolutePath.isNotEmpty && stat.version.isNotEmpty;
      final root = cacheVersion ? await _cacheRoot() : null;
      final nativeKey = cacheVersion
          ? _fnv('local\n${conn.hostId ?? 'local'}\n$sessionId\n${stat.absolutePath}\n${stat.version}')
          : null;
      final nativeDir = nativeKey == null ? null : Directory('${root!.path}/artifacts/$nativeKey');
      final nativeTarget = nativeDir == null ? null : File('${nativeDir.path}/${_safeName(artifact.name)}');
      if (nativeTarget?.existsSync() == true) return nativeTarget!;

      final host = await channel.resolveGuest(artifact.path);
      if (host != null) {
        final resolved = File(host);
        if (!cacheVersion || !_isManagedRootArtifact(resolved, root!)) return resolved;

        Directory? staging;
        try {
          await nativeDir!.create(recursive: true);
          staging = await nativeDir.createTemp('.part-');
          final staged = File('${staging.path}/${_safeName(artifact.name)}');
          if (nativeTarget!.existsSync()) return nativeTarget;
          await resolved.rename(staged.path);
          staged.renameSync(nativeTarget.path);
          return nativeTarget;
        } finally {
          if (staging != null && await staging.exists()) await staging.delete(recursive: true);
          if (await resolved.exists()) await resolved.delete();
        }
      }

      if (stat == null) {
        if (statFailure case final failure?) {
          final (error, stack) = failure;
          if (error is DshRemoteException || error is DshHttpException) {
            Error.throwWithStackTrace(error, stack);
          }
        }
        throw const ArtifactError('missing');
      }
      if (!conn.client.canReadFileBytes) throw const ArtifactError('unsupported');
      final sourcePath = stat.absolutePath.isEmpty ? artifact.path : stat.absolutePath;
      final readKey = _fnv('local-api\n${conn.hostId ?? 'local'}\n$sessionId\n$sourcePath');
      return _downloadToCache(
        client: conn.client,
        sessionId: sessionId,
        path: artifact.path,
        artifact: artifact,
        stat: stat,
        cacheKey: readKey,
        maxBytes: maxBytes,
        cacheRoot: _cacheRoot,
        onProgress: onProgress,
        requireVersion: true,
      );
    }
    final client = conn.client;
    if (!client.canReadFileBytes) throw const ArtifactError('unsupported');
    final stat = await client.statFile(sessionId, artifact.path);
    final key = _fnv('${conn.hostId ?? conn.label}\n$sessionId\n${artifact.path}');
    return _downloadToCache(
      client: client,
      sessionId: sessionId,
      path: artifact.path,
      artifact: artifact,
      stat: stat,
      cacheKey: key,
      maxBytes: maxBytes,
      cacheRoot: _cacheRoot,
      onProgress: onProgress,
    );
  }

  /// Opens [artifact] in the app Android picks for its type ([chooser]: the
  /// person picks).
  Future<void> open(
    String sessionId,
    Artifact artifact, {
    bool chooser = false,
    void Function(double?)? onProgress,
  }) async {
    final file = await materialize(sessionId, artifact, onProgress: onProgress);
    await channel.open(file.path, name: artifact.name, mime: artifact.mimeType, chooser: chooser);
  }

  /// The system share sheet with the file.
  Future<void> share(String sessionId, Artifact artifact, {void Function(double?)? onProgress}) async {
    final file = await materialize(sessionId, artifact, onProgress: onProgress);
    await _share(
      ShareParams(
        files: [XFile(file.path, mimeType: artifact.mimeType, name: artifact.name)],
        fileNameOverrides: [artifact.name],
      ),
    );
  }

  /// Copies the file into the phone's public Downloads; returns where.
  Future<String> saveToDownloads(String sessionId, Artifact artifact, {void Function(double?)? onProgress}) async {
    final file = await materialize(sessionId, artifact, onProgress: onProgress);
    return channel.saveToDownloads(file.path, name: artifact.name, mime: artifact.mimeType);
  }

  /// A small image of [artifact] for its card; null when it is not an image
  /// Flutter can draw, or too big to fetch just for this.
  Future<ImageProvider?> thumbnail(String sessionId, String path) async {
    final artifact = Artifact(path: path, origin: ArtifactOrigin.linked, turn: 0);
    if (artifact.kind != ArtifactKind.image || path.toLowerCase().endsWith('.svg')) {
      return null;
    }
    final conn = connection;
    if (conn == null || !_connectionMatchesSelection) return null;
    if (!conn.isLocal) {
      final stat = await conn.client.statFile(sessionId, artifact.path);
      if ((stat.bytes ?? maxThumbnailBytes + 1) > maxThumbnailBytes) {
        return null;
      }
    }
    final file = await materialize(sessionId, artifact, maxBytes: maxThumbnailBytes);
    if (await file.length() > maxThumbnailBytes) return null;
    return FileImage(file);
  }
}

Future<File> _downloadToCache({
  required DshClient client,
  required String sessionId,
  required String path,
  required Artifact artifact,
  required DshFileStat stat,
  required String cacheKey,
  required int maxBytes,
  required Future<Directory> Function() cacheRoot,
  required void Function(double? progress)? onProgress,
  bool requireVersion = false,
}) async {
  if (stat.bytes != null && stat.bytes! > maxBytes) throw const ArtifactError('workspace-file/too-large');
  final root = await cacheRoot();
  final dir = Directory('${root.path}/artifacts/$cacheKey');
  final target = File('${dir.path}/${_safeName(artifact.name)}');
  final marker = File('${dir.path}/.version');
  if ((!requireVersion || stat.version.isNotEmpty) &&
      target.existsSync() &&
      marker.existsSync() &&
      marker.readAsStringSync() == stat.version) {
    if (await target.length() > maxBytes) throw const ArtifactError('workspace-file/too-large');
    return target;
  }
  await dir.create(recursive: true);
  final staging = await dir.createTemp('.part-');
  final part = File('${staging.path}/${_safeName(artifact.name)}');
  final sink = part.openWrite();
  var received = 0;
  try {
    try {
      await client.readFile(
        sessionId,
        path,
        maxBytes: maxBytes,
        onChunk: (chunk) {
          received += chunk.length;
          if (received > maxBytes) throw const ArtifactError('workspace-file/too-large');
          sink.add(chunk);
        },
        onProgress: onProgress == null
            ? null
            : (received, total) => onProgress(total == null || total == 0 ? null : received / total),
      );
    } finally {
      await sink.close();
    }
    part.renameSync(target.path);
    marker.writeAsStringSync(stat.version);
    return target;
  } finally {
    await staging.delete(recursive: true);
  }
}

bool _isManagedRootArtifact(File file, Directory cacheRoot) {
  final name = file.uri.pathSegments.last;
  return file.parent.absolute.path == Directory('${cacheRoot.path}/artifacts').absolute.path &&
      name.startsWith('guest-artifact-') &&
      name.endsWith('.tmp');
}

String _safeName(String name) {
  final cleaned = name.replaceAll(RegExp(r'[\x00-\x1f/\\:*?"<>|]'), '_').trim();
  return cleaned.isEmpty ? 'file' : cleaned;
}

/// FNV-1a, hex: a stable cache directory name.
String _fnv(String text) {
  var hash = 0x811c9dc5;
  for (final unit in text.codeUnits) {
    hash ^= unit;
    hash = (hash * 0x01000193) & 0xFFFFFFFF;
  }
  return hash.toRadixString(16).padLeft(8, '0');
}

final artifactFilesProvider = Provider<ArtifactFiles>((ref) {
  final host = ref.watch(activeHostProvider);
  return ArtifactFiles(
    connection: ref.watch(activeConnectionProvider).value,
    channel: ref.watch(filesChannelProvider),
    selectedHost: host,
    rootRuntime: _rootRuntimeBuild,
  );
});

/// [ArtifactFiles.thumbnail], cached per (session, path) while shown.
final artifactThumbnailProvider = FutureProvider.autoDispose.family<ImageProvider?, (String, String)>((ref, key) async {
  final (sessionId, path) = key;
  try {
    return await ref.watch(artifactFilesProvider).thumbnail(sessionId, path);
  } catch (_) {
    return null;
  }
});
