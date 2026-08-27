import 'package:flutter/services.dart';
import 'dart:convert';

class PermissionState {
  const PermissionState({
    required this.accessibility,
    required this.battery,
    required this.overlay,
    required this.notificationAccess,
    required this.notifications,
  });
  factory PermissionState.fromMap(Map<dynamic, dynamic> map) => PermissionState(
    accessibility: map['accessibility'] == true,
    battery: map['battery'] == true,
    overlay: map['overlay'] == true,
    notificationAccess: map['notificationAccess'] == true,
    notifications: map['notifications'] == true,
  );
  final bool accessibility, battery, overlay, notificationAccess, notifications;
  bool get ready =>
      accessibility &&
      battery &&
      overlay &&
      notificationAccess &&
      notifications;
}

class CapabilityLabel {
  const CapabilityLabel({
    required this.id,
    required this.label,
    required this.description,
    required this.risk,
    required this.externalSideEffect,
    required this.requiresFreshApproval,
    required this.uiExposed,
  });
  factory CapabilityLabel.fromMap(Map<dynamic, dynamic> map) => CapabilityLabel(
    id: map['id']?.toString() ?? '',
    label: map['label']?.toString() ?? '',
    description: map['description']?.toString() ?? '',
    risk: map['risk']?.toString() ?? '',
    externalSideEffect: map['externalSideEffect'] == true,
    requiresFreshApproval: map['requiresFreshApproval'] == true,
    uiExposed: map['uiExposed'] == true,
  );
  final String id, label, description, risk;
  final bool externalSideEffect, requiresFreshApproval, uiExposed;
}

class CommitmentSummary {
  const CommitmentSummary({
    required this.id,
    required this.phase,
    required this.stepIndex,
    required this.decisionQuestion,
  });
  factory CommitmentSummary.fromMap(Map<dynamic, dynamic> map) =>
      CommitmentSummary(
        id: map['id']?.toString() ?? '',
        phase: map['phase']?.toString() ?? '',
        stepIndex: (map['stepIndex'] as num?)?.toInt() ?? 0,
        decisionQuestion: map['decisionQuestion']?.toString() ?? '',
      );
  final String id, phase, decisionQuestion;
  final int stepIndex;
}

class AgentChannel {
  AgentChannel._();
  static const _channel = MethodChannel('com.sanaa.agent/core');
  static Future<PermissionState> permissionStatus() async =>
      PermissionState.fromMap(
        await _channel.invokeMethod<Map<dynamic, dynamic>>(
              'permissionStatus',
            ) ??
            const {},
      );
  static Future<Map<String, bool>> capabilityHealth() async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'capabilityHealth',
      ))?.map((key, value) => MapEntry(key.toString(), value == true)) ??
      const {};
  static Future<List<CapabilityLabel>> capabilityCatalog() async =>
      ((await _channel.invokeMethod<List<dynamic>>('capabilityCatalog')) ??
              const [])
          .map((raw) => CapabilityLabel.fromMap(raw as Map<dynamic, dynamic>))
          .toList(growable: false);

  static Future<List<CapabilityLabel>> exposedCapabilities() async =>
      (await capabilityCatalog())
          .where((c) => c.uiExposed)
          .toList(growable: false);

  static Future<List<CommitmentSummary>> activeCommitments() async =>
      ((await _channel.invokeMethod<List<dynamic>>('activeCommitments')) ??
              const [])
          .map((raw) => CommitmentSummary.fromMap(raw as Map<dynamic, dynamic>))
          .toList(growable: false);

  static Future<void> openAccessibility() =>
      _channel.invokeMethod('openAccessibility');
  static Future<void> openBattery() => _channel.invokeMethod('openBattery');
  static Future<void> openOverlay() => _channel.invokeMethod('openOverlay');
  static Future<void> openNotificationAccess() =>
      _channel.invokeMethod('openNotifications');
  static Future<void> requestNotifications() =>
      _channel.invokeMethod('requestNotifications');
  static Future<void> requestContactsPermission() =>
      _channel.invokeMethod('requestContactsPermission');
  static Future<void> saveGroqKey(String key) =>
      _channel.invokeMethod('saveGroqKey', {'key': key});
  static Future<bool> storeSokoPin(String pin) async =>
      await _channel.invokeMethod<bool>('storeSokoPin', {'pin': pin}) ?? false;
  static Future<bool> hasGroqKey() async =>
      await _channel.invokeMethod<bool>('hasGroqKey') ?? false;
  static Future<String> testGroq() async =>
      await _channel.invokeMethod<String>('testGroq') ?? 'Connected';
  static Future<bool> startAgent() async =>
      await _channel.invokeMethod<bool>('startAgent') ?? false;
  static Future<bool> setupComplete() async =>
      await _channel.invokeMethod<bool>('setupComplete') ?? false;
  static Future<bool> syncConfig() async =>
      await _channel.invokeMethod<bool>('syncConfig') ?? false;
  static Future<Map<String, dynamic>> agentStatus() async {
    final value = await _channel.invokeMethod<String>('agentStatus') ?? '{}';
    return jsonDecode(value) as Map<String, dynamic>;
  }

  static Future<Map<String, dynamic>> submitTask({
    required String command,
    required String contactName,
    required String contactPhone,
    required DateTime runAt,
  }) async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>('submitTask', {
        'command': command,
        'contactName': contactName,
        'contactPhone': contactPhone,
        'runAt': runAt.millisecondsSinceEpoch,
      }))?.cast<String, dynamic>() ??
      const {
        'success': false,
        'status': 'failed',
        'message': 'No result returned',
      };
  static Future<List<Map<String, dynamic>>> chatHistory() async =>
      ((await _channel.invokeMethod<List<dynamic>>('chatHistory')) ?? const [])
          .map((item) => (item as Map).cast<String, dynamic>())
          .toList();

  static Future<Map<String, dynamic>?> latestTaskReceipt() async {
    final value = await _channel.invokeMethod<dynamic>('latestTaskReceipt');
    return value is Map ? value.cast<String, dynamic>() : null;
  }

  static Future<Map<String, dynamic>> autonomyStatus() async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'autonomyStatus',
      ))?.cast<String, dynamic>() ??
      const {'phase': 'idle', 'detail': 'Ready for the next thing'};

  static Future<Map<String, dynamic>> runtimeStatusSnapshot() async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'runtimeStatusSnapshot',
      ))?.cast<String, dynamic>() ??
      const {'phase': 'idle', 'detail': 'Ready for the next thing', 'isReady': false};

  static Future<Map<String, dynamic>> missionControl() async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'missionControl',
      ))?.cast<String, dynamic>() ??
      const {'approvals': [], 'findings': [], 'schedules': [], 'settings': {}};

  static Future<bool> setProactiveReadOnlyAudits(bool enabled) async =>
      await _channel.invokeMethod<bool>('setProactiveReadOnlyAudits', {
        'enabled': enabled,
      }) ??
      false;

  static Future<bool> decideApproval(int id, bool approve) async =>
      await _channel.invokeMethod<bool>('decideApproval', {
        'id': id,
        'approve': approve,
      }) ??
      false;

  static Future<bool> setScheduleEnabled(int id, bool enabled) async =>
      await _channel.invokeMethod<bool>('setScheduleEnabled', {
        'id': id,
        'enabled': enabled,
      }) ??
      false;

  static Future<bool> stopCurrentTask() async =>
      await _channel.invokeMethod<bool>('stopCurrentTask') ?? false;

  static Future<List<Map<String, dynamic>>> contactPermissions() async =>
      ((await _channel.invokeMethod<List<dynamic>>('contactPermissions')) ??
              const [])
          .map((item) => (item as Map).cast<String, dynamic>())
          .toList();

  static Future<bool> setContactPermission({
    required String name,
    required String? number,
    required bool isGroup,
    required String permission,
    String? contactId,
  }) async =>
      await _channel.invokeMethod<bool>('setContactPermission', {
        'name': name,
        'number': number,
        'isGroup': isGroup,
        'permission': permission,
        if (contactId != null && contactId.isNotEmpty) 'contactId': contactId,
      }) ??
      false;

  static Future<List<Map<String, dynamic>>> discoverWhatsAppContacts() async =>
      ((await _channel.invokeMethod<List<dynamic>>(
                'discoverWhatsAppContacts',
              )) ??
              const [])
          .map((item) => (item as Map).cast<String, dynamic>())
          .toList();

  static Future<List<Map<String, dynamic>>> discoverAllContacts() async =>
      ((await _channel.invokeMethod<List<dynamic>>('discoverAllContacts')) ??
              const [])
          .map((item) => (item as Map).cast<String, dynamic>())
          .toList();

  static Future<Map<String, int>> setAllContactPermissions(
    String permission,
  ) async =>
      ((await _channel.invokeMethod<Map<dynamic, dynamic>>(
                'setAllContactPermissions',
                {'permission': permission},
              )) ??
              const {})
          .map(
            (key, value) =>
                MapEntry(key.toString(), value is num ? value.toInt() : 0),
          );

  static Future<Map<String, dynamic>?> pickAttachment() async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'pickAttachment',
      ))?.cast<String, dynamic>();

  static Future<bool> sendWhatsAppAttachment({
    required String uri,
    required String mimeType,
    required String target,
    required String caption,
  }) async =>
      await _channel.invokeMethod<bool>('sendWhatsAppAttachment', {
        'uri': uri,
        'mimeType': mimeType,
        'target': target,
        'caption': caption,
      }) ??
      false;

  static Future<bool> postWhatsAppMediaStatus({
    required String uri,
    required String mimeType,
    required String caption,
  }) async =>
      await _channel.invokeMethod<bool>('postWhatsAppMediaStatus', {
        'uri': uri,
        'mimeType': mimeType,
        'caption': caption,
      }) ??
      false;

  static Future<List<Map<String, dynamic>>> selfHealingDiagnose() async =>
      ((await _channel.invokeMethod<List<dynamic>>('selfHealingDiagnose')) ??
              const [])
          .map((item) => (item as Map).cast<String, dynamic>())
          .toList();

  static Future<bool> selfHealingFix(String key) async =>
      await _channel.invokeMethod<bool>('selfHealingFix', {'key': key}) ??
      false;

  static Future<bool> selfHealingFixAll() async =>
      await _channel.invokeMethod<bool>('selfHealingFixAll') ?? false;

  static Future<Map<String, dynamic>?> pickContact() async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'pickContact',
      ))?.cast<String, dynamic>();

  static Future<Map<String, dynamic>?> pickWhatsAppContact() async =>
      (await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'pickWhatsAppContact',
      ))?.cast<String, dynamic>();

  static Future<bool> isAccessibilityBound() async =>
      await _channel.invokeMethod<bool>('isAccessibilityBound') ?? false;

  static Future<Map<String, dynamic>?> revenueDashboard() async =>
      await _channel.invokeMethod<Map<dynamic, dynamic>>('revenueDashboard')
          as Map<String, dynamic>?;

  // ---- Owner commerce controls: policy, consent, sales, opt-outs ----

  static Future<String?> commercialPolicyGet() async =>
      await _channel.invokeMethod<String>('commercialPolicyGet');

  static Future<bool> commercialPolicySave(String policyJson) async =>
      await _channel.invokeMethod<bool>('commercialPolicySave', {
        'policyJson': policyJson,
      }) ??
      false;

  static Future<List<Map<String, dynamic>>> consentLedger() async =>
      ((await _channel.invokeMethod<List<dynamic>>('consentLedger')) ??
              const [])
          .map((e) => (e as Map).cast<String, dynamic>())
          .toList();

  static Future<bool> grantContactConsent({
    required String contactKey,
    required String evidenceRef,
    List<String> products = const [],
    List<String> channels = const ['whatsapp'],
    num? expiresAtMs,
  }) async =>
      await _channel.invokeMethod<bool>('grantContactConsent', {
        'contactKey': contactKey,
        'scope': 'outreach',
        'evidenceRef': evidenceRef,
        'products': products,
        'channels': channels,
        if (expiresAtMs != null) 'expiresAtMs': expiresAtMs,
      }) ??
      false;

  static Future<bool> revokeContactConsent(
    String contactKey,
    String reason,
  ) async =>
      await _channel.invokeMethod<bool>('revokeContactConsent', {
        'contactKey': contactKey,
        'reason': reason,
      }) ??
      false;

  static Future<String> confirmSaleByOwner({
    required String saleRef,
    required String contactKey,
    required String productRef,
    required num amountUgx,
  }) async =>
      await _channel.invokeMethod<String>('confirmSaleByOwner', {
        'saleRef': saleRef,
        'contactKey': contactKey,
        'productRef': productRef,
        'amountUgx': amountUgx,
      }) ??
      '';

  static Future<bool> recordCommercialOptOut(
    String contactKey,
    String reason,
  ) async =>
      await _channel.invokeMethod<bool>('recordCommercialOptOut', {
        'contactKey': contactKey,
        'reason': reason,
      }) ??
      false;

  static Future<void> openAccessibilitySettings() async =>
      await _channel.invokeMethod<void>('openAccessibility');
}
