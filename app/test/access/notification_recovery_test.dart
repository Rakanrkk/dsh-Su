// Notification permission recovery retries queued Root notifications first;
// Android settings opens only when no pending post was delivered.

import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/runtime/background_net.dart' show backgroundSettingsProvider;
import 'package:dsh_app/state/access_state.dart';
import 'package:dsh_app/state/notify_state.dart';
import 'package:dsh_app/state/phone_state.dart' show phoneChannelProvider, phoneProvider;
import 'package:dsh_app/pets/overlay_channel.dart';
import 'package:dsh_app/phone/phone_channel.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../notify/support.dart';

class _Runtime extends LocalRuntimeController {
  _Runtime(this.root);

  final bool root;

  @override
  LocalRuntimeState build() => LocalRuntimeState(
    loaded: true,
    status: RuntimeStatus(phase: RuntimePhase.installed, rootRuntime: root),
  );
}

class _StatusRuntime extends LocalRuntimeController {
  _StatusRuntime(this.status);

  final RuntimeStatus status;

  @override
  LocalRuntimeState build() => LocalRuntimeState(loaded: true, status: status);
}

class _ReadRuntimeBridge extends RuntimeBridge {
  @override
  Future<StorageAccess> storageStatus() async =>
      const StorageAccess(granted: false, permission: false, legacy: false);

  @override
  Future<bool?> batteryExempt() async => null;

  @override
  Future<bool?> networkMetered() async => null;
}

class _NoPhone extends PhoneChannel {
  @override
  Future<PhoneStatus> status() async => const PhoneStatus.notReady();
}

class _Pet extends PetOverlayController {
  @override
  PetOverlayStatus build() => const PetOverlayStatus.unavailable();

  @override
  Future<PetOverlayStatus> refresh() async => const PetOverlayStatus.unavailable();
}

class _Alerts extends ChatAlertsController {
  _Alerts(this.result);

  final bool result;
  int retries = 0;

  @override
  ChatAlert? build() => null;

  @override
  Future<bool> retryFailedNotifications() async {
    retries++;
    return result;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('Root retry success keeps the user in the app', () async {
    final channel = FakeNotifyChannel();
    final alerts = _Alerts(true);
    final container = ProviderContainer(
      overrides: [
        localRuntimeProvider.overrideWith(() => _Runtime(true)),
        phoneChannelProvider.overrideWithValue(_NoPhone()),
        notifyChannelProvider.overrideWithValue(channel),
        chatAlertsProvider.overrideWith(() => alerts),
      ],
    );
    addTearDown(container.dispose);

    expect(await container.read(accessDriverProvider).open(AccessItem.notifications), isTrue);
    expect(alerts.retries, 1);
    expect(channel.openedSettings, isEmpty);
  });

  test('Root denial falls through to Android notification settings', () async {
    final channel = FakeNotifyChannel();
    final alerts = _Alerts(false);
    final container = ProviderContainer(
      overrides: [
        localRuntimeProvider.overrideWith(() => _Runtime(true)),
        notifyChannelProvider.overrideWithValue(channel),
        chatAlertsProvider.overrideWith(() => alerts),
      ],
    );
    addTearDown(container.dispose);

    expect(await container.read(accessDriverProvider).open(AccessItem.notifications), isTrue);
    expect(alerts.retries, 1);
    expect(channel.openedSettings, [null]);
  });

  test('normal builds keep the existing settings path', () async {
    final channel = FakeNotifyChannel();
    final alerts = _Alerts(true);
    final container = ProviderContainer(
      overrides: [
        localRuntimeProvider.overrideWith(() => _Runtime(false)),
        notifyChannelProvider.overrideWithValue(channel),
        chatAlertsProvider.overrideWith(() => alerts),
      ],
    );
    addTearDown(container.dispose);

    expect(await container.read(accessDriverProvider).open(AccessItem.notifications), isTrue);
    expect(alerts.retries, 0);
    expect(channel.openedSettings, [null]);
  });

  test('Root access readings use RuntimeStatus when the phone bridge is unavailable', () async {
    final container = ProviderContainer(
      overrides: [
        localRuntimeProvider.overrideWith(
          () => _StatusRuntime(RuntimeStatus.fromMap({'phase': 'installed', 'rootRuntime': true})),
        ),
        runtimeBridgeProvider.overrideWithValue(_ReadRuntimeBridge()),
        phoneChannelProvider.overrideWithValue(_NoPhone()),
        notifyChannelProvider.overrideWithValue(FakeNotifyChannel(system: const NotifySystemStatus.unavailable())),
        petOverlayProvider.overrideWith(_Pet.new),
        backgroundSettingsProvider.overrideWith((ref) async => null),
      ],
    );
    addTearDown(container.dispose);

    final readings = await container.read(accessDriverProvider).read();
    final phoneStatus = container.read(phoneProvider).status;
    expect(phoneStatus.ready, isFalse);
    expect(phoneStatus.rootRuntime, isFalse);
    expect(readings.rootRuntime, isTrue);

    final walk = accessWalk({AccessFeature.files, AccessFeature.phone}, null, rootRuntime: readings.rootRuntime);
    expect(walk, isNot(contains(AccessItem.storage)));
    expect(walk, isNot(contains(AccessItem.notifications)));
  });

  test('a legacy RuntimeStatus without rootRuntime keeps normal access items', () {
    final status = RuntimeStatus.fromMap({'phase': 'installed'});
    expect(status.rootRuntime, isFalse);
    expect(
      accessWalk({AccessFeature.files, AccessFeature.phone}, null, rootRuntime: status.rootRuntime),
      containsAll([AccessItem.storage, AccessItem.notifications]),
    );
  });

  test('SystemAccessDriver keeps legacy access items with a missing rootRuntime field', () async {
    final container = ProviderContainer(
      overrides: [
        localRuntimeProvider.overrideWith(
          () => _StatusRuntime(RuntimeStatus.fromMap({'phase': 'installed'})),
        ),
        runtimeBridgeProvider.overrideWithValue(_ReadRuntimeBridge()),
        phoneChannelProvider.overrideWithValue(_NoPhone()),
        notifyChannelProvider.overrideWithValue(FakeNotifyChannel(system: const NotifySystemStatus.unavailable())),
        petOverlayProvider.overrideWith(_Pet.new),
        backgroundSettingsProvider.overrideWith((ref) async => null),
      ],
    );
    addTearDown(container.dispose);

    final readings = await container.read(accessDriverProvider).read();
    expect(container.read(phoneProvider).status.ready, isFalse);
    expect(readings.rootRuntime, isFalse);
    expect(
      accessWalk({AccessFeature.files, AccessFeature.phone}, null, rootRuntime: readings.rootRuntime),
      containsAll([AccessItem.storage, AccessItem.notifications]),
    );
  });
}
