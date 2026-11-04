import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../runtime/runtime_bridge.dart';
import '../runtime/runtime_state.dart';

/// Multi-step engine actions built on [localRuntimeProvider].
///
/// They take the [ProviderContainer] (not a WidgetRef) so they keep working after
/// the screen that started them is popped.
abstract final class EngineControl {
  /// Stops the local DSH, waits until the process has actually exited, then starts it again.
  /// [mode] null keeps the current mode (a restart in safe mode stays in safe mode).
  static Future<void> restart(
    ProviderContainer container, {
    EngineMode? mode,
    bool Function()? isCurrent,
    Future<void>? cancelRequested,
    Duration stopTimeout = const Duration(seconds: 20),
  }) async {
    final runtime = container.read(localRuntimeProvider.notifier);
    final phase = container.read(localRuntimeProvider).status.phase;
    if (phase == RuntimePhase.running || phase == RuntimePhase.starting || phase == RuntimePhase.stopping) {
      await _stopAndWait(container, timeout: stopTimeout);
    }
    if (isCurrent != null && !isCurrent()) throw StateError('restart cancelled');
    await _startAndWait(
      container,
      () => mode == null ? runtime.startOwned() : runtime.startInOwned(mode),
      isCurrent: isCurrent,
      cancelRequested: cancelRequested,
      stopOwnedStart: runtime.stopOwnedStart,
    );
  }

  /// Starts the engine unless it is already starting or running.
  static Future<void> ensureStarted(ProviderContainer container) async {
    final phase = container.read(localRuntimeProvider).status.phase;
    if (phase == RuntimePhase.installed || phase == RuntimePhase.error) {
      await container.read(localRuntimeProvider.notifier).start();
    }
  }

  /// Whether the launch-time auto start should run. After two failures in a row with
  /// automatic safe mode turned off, the failure card waits for the user instead.
  /// In every other case `start()` itself picks the right mode, so callers need
  /// nothing else.
  static bool shouldAutoStart(RuntimeStatus status) =>
      !(status.safeState == SafeModeState.off && !status.autoSafe && status.strikes >= 2);

  /// Restarts in safe mode, first renaming the given home-level files out of the way.
  static Future<void> enterSafeMode(ProviderContainer container, {List<String> moveAside = const []}) async {
    if (moveAside.isNotEmpty) {
      await container.read(runtimeBridgeProvider).moveAside(moveAside);
    }
    await restart(container, mode: EngineMode.safe);
  }

  /// Back to the user's own profile; failing again within two minutes returns to safe mode.
  static Future<void> leaveSafeMode(ProviderContainer container) => restart(container, mode: EngineMode.normal);

  /// Puts the main profile back to its last good plugin set, then starts in normal mode
  /// (from safe mode that is the two-minute trial). Needs no model, key or network.
  static Future<LastKnownGood> restoreLastKnownGood(ProviderContainer container) async {
    final status = container.read(localRuntimeProvider).status;
    final busy =
        status.phase == RuntimePhase.running ||
        status.phase == RuntimePhase.starting ||
        status.phase == RuntimePhase.stopping;
    // A normal-mode DSH must not be running on the profile being rewritten; a safe one may.
    if (busy && !status.inSafeMode) await _stopAndWait(container);
    final changed = await container.read(runtimeBridgeProvider).restoreLastKnownGood();
    await restart(container, mode: EngineMode.normal);
    return changed;
  }

  static Future<void> _stopAndWait(
    ProviderContainer container, {
    Duration timeout = const Duration(seconds: 20),
  }) async {
    final stopped = Completer<void>();
    final sub = container.listen<RuntimePhase>(localRuntimeProvider.select((s) => s.status.phase), (_, next) {
      if (!next.isBusy && next != RuntimePhase.running && !stopped.isCompleted) {
        stopped.complete();
      }
    });
    try {
      final stop = container.read(localRuntimeProvider).status.phase == RuntimePhase.stopping
          ? Future<void>.value()
          : container.read(localRuntimeProvider.notifier).stop();
      await Future.wait<void>([stop, stopped.future], eagerError: true).timeout(timeout);
    } finally {
      sub.close();
    }
  }

  static Future<void> _startAndWait(
    ProviderContainer container,
    Future<int> Function() start, {
    bool Function()? isCurrent,
    Future<void>? cancelRequested,
    required Future<void> Function(int ticket) stopOwnedStart,
  }) async {
    final ready = Completer<void>();
    var starting = false;
    void observe(RuntimePhase phase) {
      if (phase == RuntimePhase.starting) starting = true;
      if (phase == RuntimePhase.running) {
        if (!ready.isCompleted) ready.complete();
      } else if (starting &&
          (phase == RuntimePhase.error || phase == RuntimePhase.installed || phase == RuntimePhase.notInstalled)) {
        if (!ready.isCompleted) {
          final error = container.read(localRuntimeProvider).status.error;
          ready.completeError(StateError(error?.isNotEmpty == true ? error! : 'runtime stopped before becoming ready'));
        }
      }
    }

    final sub = container.listen<RuntimePhase>(
      localRuntimeProvider.select((s) => s.status.phase),
      (_, next) => observe(next),
    );
    final readyResult = ready.future.then<(bool, Object?, StackTrace?)>(
      (_) => (false, null, null),
      onError: (Object error, StackTrace stack) => (false, error, stack),
    );
    final cancelResult = cancelRequested?.then<(bool, Object?, StackTrace?)>((_) => (true, null, null));
    final outcomeFuture = cancelResult == null ? readyResult : Future.any([readyResult, cancelResult]);
    try {
      if (isCurrent != null && !isCurrent()) throw StateError('restart cancelled');
      final startTicket = await start();
      observe(container.read(localRuntimeProvider).status.phase);
      if (startTicket <= 0) throw StateError('runtime start returned no owner ticket');
      final (wasCancelled, error, stack) = await outcomeFuture;
      if (wasCancelled || (isCurrent != null && !isCurrent())) {
        await stopOwnedStart(startTicket);
        throw StateError('restart cancelled');
      }
      if (error != null) Error.throwWithStackTrace(error, stack!);
    } finally {
      sub.close();
    }
  }
}
