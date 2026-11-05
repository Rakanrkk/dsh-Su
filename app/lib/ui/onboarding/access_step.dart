// First run, step 3 of 4 on this phone, while the runtime unpacks
// (docs/design/onboarding-parallel-setup.md): what DSH Go should do, then
// each permission it needs, one at a time, then where they all ended up.

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_state.dart';
import '../../state/access_state.dart';
import '../../state/phone_state.dart';
import '../access/access_pick_page.dart';
import '../access/access_summary_page.dart';
import '../access/access_walk.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'onboarding_scaffold.dart';

/// 想让 DSH Go 帮你做哪些事 → 逐项开启 → 汇总. 跳过 on the first page goes on
/// with nothing changed. The picks are kept until the step is over, so a
/// launch after the app was killed in system settings comes back to the walk.
class AccessStepPage extends ConsumerStatefulWidget {
  const AccessStepPage({super.key, required this.onDone});

  final VoidCallback onDone;

  @override
  ConsumerState<AccessStepPage> createState() => _AccessStepPageState();
}

class _AccessStepPageState extends ConsumerState<AccessStepPage> {
  bool _summary = false;
  bool _walkedOnce = false;
  bool _finishing = false;
  bool _choosing = false;
  bool _rootLoading = false;
  Object? _rootError;
  Future<void>? _pendingRoot;
  bool _rootCompleted = false;

  Future<void> _ensureRootAccess() async {
    final run = _pendingRoot;
    if (run != null) return run;
    final pending = _runRootAccess();
    _pendingRoot = pending;
    return pending.whenComplete(() {
      if (identical(_pendingRoot, pending)) _pendingRoot = null;
    });
  }

  Future<void> _runRootAccess() async {
    setState(() {
      _rootLoading = true;
      _rootError = null;
    });
    try {
      await ref.read(runtimeBridgeProvider).ensureRootAccess();
      await ref.read(accessReadingsProvider.notifier).refresh();
      if (mounted) {
        setState(() => _rootCompleted = true);
        widget.onDone();
      }
    } catch (error) {
      if (mounted) {
        final detail = switch (error) {
          PlatformException(:final message, :final details) => [
            ?message,
            if (details case final String detail when detail.isNotEmpty) detail,
          ].join(' · '),
          _ => error.toString(),
        };
        setState(() => _rootError = detail.isEmpty ? error.toString() : detail);
      }
    } finally {
      if (mounted) setState(() => _rootLoading = false);
    }
  }

  Widget _rootPage(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    return OnboardingScaffold(
      children: [
        const Spacer(),
        OnboardingHeader(
          key: const ValueKey('root-access-header'),
          icon: AppIcons.systemAccess,
          title: l10n.accessRootTitle,
          message: l10n.accessRootMessage,
        ),
        const SizedBox(height: 16),
        Text(
          _rootLoading ? l10n.accessRootConfiguring : l10n.accessRootManagerNote,
          key: ValueKey(_rootLoading ? 'root-access-configuring' : 'root-access-manager-note'),
          style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        if (_rootError != null) ...[
          const SizedBox(height: 12),
          Text(
            '${l10n.accessRootFailed}: $_rootError',
            key: const ValueKey('root-access-error'),
            style: theme.textTheme.bodySmall?.copyWith(color: DshColors.of(context).warning),
          ),
        ],
        const Spacer(flex: 2),
        const SizedBox(height: 24),
        FilledButton(
          key: const ValueKey('root-access-primary'),
          onPressed: _rootLoading ? null : () => unawaited(_ensureRootAccess()),
          child: Text(_rootError == null ? l10n.accessRootAuthorize : l10n.commonRetry),
        ),
      ],
    );
  }

  Future<void> _walked() async {
    if (_walkedOnce) return;
    _walkedOnce = true;
    await ref.read(accessReadingsProvider.notifier).refresh();
    if (mounted) setState(() => _summary = true);
  }

  /// 继续: what was picked goes on as the summary is left, so an item turned
  /// on from it counts ([AccessSetupController.finish]). If that fails (the
  /// readings could not be taken) the step is not over and nothing is lost:
  /// it says so with 重试 (over 继续 while it shows), and 继续 works again.
  Future<void> _finish() async {
    if (_finishing) return;
    _finishing = true;
    try {
      await ref.read(accessSetupProvider.notifier).finish();
    } catch (_) {
      _finishing = false;
      if (mounted) {
        final l10n = context.l10n;
        ScaffoldMessenger.maybeOf(context)
          ?..hideCurrentSnackBar()
          ..showSnackBar(
            SnackBar(
              content: Text(l10n.accessFinishFailed),
              // Goes by itself: 继续 underneath does the same.
              persist: false,
              duration: const Duration(seconds: 6),
              action: SnackBarAction(label: l10n.commonRetry, onPressed: () => unawaited(_finish())),
            ),
          );
      }
      return;
    }
    widget.onDone();
  }

  Future<void> _choose(Set<AccessFeature> features, PhoneConfirmMode? phoneMode) async {
    if (_choosing) return;
    setState(() => _choosing = true);
    try {
      await ref.read(accessSetupProvider.notifier).choose(features, phoneMode: phoneMode);
      if (features.contains(AccessFeature.phone)) {
        final readings =
            ref.read(accessReadingsProvider).value ?? await ref.read(accessReadingsProvider.notifier).refresh();
        if (readings.rootRuntime) {
          try {
            final phone = ref.read(phoneProvider.notifier);
            await phone.setEnabled(true);
            await phone.refreshStatus();
            await ref.read(accessReadingsProvider.notifier).refresh();
          } catch (_) {
            // The existing phone-control settings page remains the recovery path.
          }
        }
      }
    } catch (_) {
    } finally {
      if (mounted) setState(() => _choosing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final setup = ref.watch(accessSetupProvider);
    final readings = ref.watch(accessReadingsProvider).value;
    final features = setup.features;
    final rootOnly = ref.watch(localRuntimeProvider.select((s) => s.status.rootRuntime));
    final rootDone = rootOnly && _rootCompleted && features == null;
    if (rootOnly &&
        setup.loaded &&
        features == null &&
        !_rootLoading &&
        _rootError == null &&
        _pendingRoot == null &&
        !_rootCompleted) {
      unawaited(_ensureRootAccess());
    }
    final Widget page;
    if (rootDone) {
      page = const OnboardingScaffold(key: ValueKey('root-access-done'), children: [SizedBox.shrink()]);
    } else if (rootOnly) {
      page = _rootPage(context);
    } else if (_choosing) {
      page = const OnboardingScaffold(key: ValueKey('choosing'), children: [SizedBox.shrink()]);
    } else if (setup.loaded && features == null) {
      // The pick needs nothing from the phone (mobile data, if known, shows when read).
      page = AccessPickPage(
        key: const ValueKey('pick'),
        onPicked: (picked, phoneMode) => unawaited(_choose(picked, phoneMode)),
        onSkip: widget.onDone,
      );
    } else if (features == null || readings == null) {
      page = const OnboardingScaffold(key: ValueKey('loading'), children: [SizedBox.shrink()]);
    } else {
      final summaryItems = accessWalk(
        features,
        readings.vendor,
      ).where((item) => item != AccessItem.storage || readings.storage != true).toList();
      final walkItems = accessWalk(
        features,
        readings.vendor,
        rootRuntime: readings.rootRuntime,
      ).where((item) => item != AccessItem.storage || readings.storage != true).toList();
      page = _summary
          ? AccessSummaryPage(
              key: const ValueKey('summary'),
              items: summaryItems,
              voice: features.contains(AccessFeature.voice),
              onDone: () => unawaited(_finish()),
            )
          : AccessWalk(
              key: const ValueKey('walk'),
              items: walkItems,
              vendor: readings.vendor,
              skipped: setup.skipped,
              onSkip: (item) => unawaited(ref.read(accessSetupProvider.notifier).skip(item)),
              onDone: () => unawaited(_walked()),
            );
    }
    return OnboardingSwitcher(child: page);
  }
}
