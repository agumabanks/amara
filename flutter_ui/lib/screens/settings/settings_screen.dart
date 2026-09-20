import 'dart:async';
import 'package:flutter/services.dart';
import 'whatsapp_groups_screen.dart';

import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';
import '../contacts/contact_permissions_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  Map<String, dynamic> _settings = const {};
  bool _loading = true;
  bool _refreshing = false;
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadSettings();
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 15),
      (_) => _loadSettings(),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _loadSettings();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _refreshTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadSettings() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final settings = await AgentChannel.amaraSettings();
      if (mounted) {
        setState(() {
          _settings = settings;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _setYouTubeChannel() async {
    final input = TextEditingController(
      text: _settings['youtubeChannel'] as String? ?? '',
    );
    final value = await showDialog<String>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: const Text('YouTube channel'),
        content: TextField(
          controller: input,
          decoration: const InputDecoration(labelText: '@channel'),
          autocorrect: false,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialog),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialog, input.text.trim()),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (value != null && mounted) await _updateSetting('youtubeChannel', value);
    input.dispose();
  }

  Widget _moduleStats(String module) {
    final all = _settings['moduleStats'] as Map? ?? {};
    final stats = all[module] as Map? ?? {};
    final recent = (stats['recent'] as List? ?? []).whereType<Map>();
    return ExpansionTile(
      title: Text('$module activity'),
      subtitle: const Text('Last 24 hours'),
      children: [
        ListTile(
          title: Text(
            '${stats['completed'] ?? 0} tasks completed · ${stats['failed'] ?? 0} failed',
          ),
          subtitle: Text(
            '${stats['attempts'] ?? 0} recorded attempts · ${stats['held'] ?? 0} held · ${stats['skipped'] ?? 0} skipped · ${stats['partial'] ?? 0} partial\n${stats['verified'] ?? 0} verified external actions · ${stats['uncertain'] ?? 0} uncertain',
          ),
        ),
        if (recent.isEmpty)
          const ListTile(
            title: Text('No recorded task outcomes in this window'),
          ),
        for (final row in recent)
          ListTile(
            title: Text('${row['kind']} · ${row['status']}'),
            subtitle: Text('${row['detail'] ?? ''}'),
          ),
      ],
    );
  }

  Future<void> _pairDevice() async {
    final deviceId = await AgentChannel.pairingDeviceId();
    if (!mounted) return;
    final controller = TextEditingController();
    final code = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Connect to Cards admin'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SelectableText('Device ID: $deviceId'),
            const SizedBox(height: 12),
            const Text(
              'In Cards → Devices, select this exact device and generate a pairing code. Your memories stay on this account.',
            ),
            TextField(
              controller: controller,
              autofocus: true,
              autocorrect: false,
              enableSuggestions: false,
              maxLength: 24,
              decoration: const InputDecoration(labelText: 'Pairing code'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(dialogContext, controller.text.trim()),
            child: const Text('Connect'),
          ),
        ],
      ),
    );
    await Future<void>.delayed(const Duration(milliseconds: 300));
    controller.dispose();
    if (code == null || code.isEmpty || !mounted) return;
    try {
      final synced = await AgentChannel.pairDevice(code);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              synced
                  ? 'Connected to Cards. Device settings synced.'
                  : 'Connected to Cards. Settings will retry on the next sync.',
            ),
          ),
        );
      }
    } on PlatformException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              error.message ??
                  'Could not connect. Check the device and pairing code.',
            ),
          ),
        );
      }
    }
  }

  Future<void> _updateSetting(String key, dynamic value) async {
    try {
      final saved = await AgentChannel.setAmaraSetting(key, value);
      if (!saved) throw const FormatException('Setting was rejected');
      await _loadSettings();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Could not save this setting. Check the value and try again.',
            ),
          ),
        );
      }
    }
  }

  Future<void> _setPublicAdWhatsApp({bool manager = false}) async {
    final key = manager ? 'managerWhatsApp' : 'publicAdWhatsApp';
    final controller = TextEditingController(
      text: _settings[key] as String? ?? '',
    );
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(manager ? 'Manager WhatsApp' : 'Public ad WhatsApp'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.phone,
          decoration: InputDecoration(
            labelText: 'WhatsApp number',
            helperText: manager
                ? 'Receives inquiries and order updates. Blank disables reports.'
                : 'Shown publicly on ads. Blank uses the item page.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              final raw = controller.text.trim();
              if (raw.isNotEmpty &&
                  !RegExp(r'^\+?[0-9 ()-]{8,22}$').hasMatch(raw)) {
                return;
              }
              Navigator.pop(context, raw);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (value != null && mounted) {
      try {
        final saved = await AgentChannel.setAmaraSetting(key, value);
        if (!saved) throw const FormatException('Invalid WhatsApp number');
        await _loadSettings();
      } catch (_) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Could not save the WhatsApp number.'),
            ),
          );
        }
      }
    }
  }

  Future<void> _memoryAction(bool restore) async {
    final response = restore
        ? await AgentChannel.restoreMemoryNow()
        : await AgentChannel.backupMemoryNow();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          response['message']?.toString() ?? 'Memory operation finished',
        ),
      ),
    );
    _loadSettings();
  }

  Future<void> _setVisionModel() async {
    final controller = TextEditingController(
      text: _settings['groqVisionModel']?.toString() ?? '',
    );
    final model = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Groq vision model'),
        content: TextField(
          controller: controller,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: 'Model ID',
            hintText: 'qwen/qwen3.6-27b',
            helperText: 'Use a vision model available to your Groq account.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(dialogContext, controller.text.trim()),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (model != null && mounted) {
      await _updateSetting('groqVisionModel', model);
    }
  }

  Future<void> _setSokoPin() async {
    final controller = TextEditingController();
    String? error;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Soko Terminal PIN'),
          content: TextField(
            controller: controller,
            obscureText: true,
            enableSuggestions: false,
            autocorrect: false,
            keyboardType: TextInputType.number,
            maxLength: 12,
            decoration: InputDecoration(
              labelText: 'Enter Terminal PIN',
              helperText:
                  'Encrypted on this phone. Saving allows a new login attempt after a local PIN lockout.',
              errorText: error,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () async {
                if (!RegExp(r'^[0-9]{4,12}$').hasMatch(controller.text)) {
                  setDialogState(() => error = 'Enter 4–12 digits');
                  return;
                }
                try {
                  final saved = await AgentChannel.storeSokoPin(
                    controller.text,
                  );
                  if (!dialogContext.mounted) return;
                  if (saved) {
                    controller.clear();
                    Navigator.pop(dialogContext);
                  } else {
                    setDialogState(
                      () => error = 'PIN was not saved. Try again.',
                    );
                  }
                } catch (_) {
                  if (dialogContext.mounted) {
                    setDialogState(
                      () => error = 'PIN was not saved. Try again.',
                    );
                  }
                }
              },
              child: const Text('Save PIN'),
            ),
          ],
        ),
      ),
    );
    controller.clear();
    // Dispose after the dialog's closing animation releases its text field.
    await Future<void>.delayed(const Duration(milliseconds: 300));
    controller.dispose();
    if (mounted) _loadSettings();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text(
          'Settings',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
        actions: [
          IconButton(
            onPressed: _loadSettings,
            icon: const Icon(Icons.refresh, color: Colors.white38),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
              children: [
                _buildSectionHeader('AMARA POWER'),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    _settings['amaraOn'] == false
                        ? 'Amara is off'
                        : 'Amara is on',
                  ),
                  subtitle: const Text(
                    'Pause new work when off. Actions already sent may still finish.',
                  ),
                  value: _settings['amaraOn'] != false,
                  onChanged: (value) => _updateSetting('amaraOn', value),
                ),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Connect to Cards admin'),
                  subtitle: const Text('Pair or reconnect this device.'),
                  trailing: const Icon(Icons.devices),
                  onTap: _pairDevice,
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text('Soko Access'),
                  children: [
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Verify logged-in shop'),
                      subtitle: Text(
                        (_settings['terminalShop'] as Map?)?['verified'] == true
                            ? 'Verified: ${(_settings['terminalShop'] as Map)['shopName']}. Tap to inspect its catalogue.'
                            : 'Open Terminal to refresh its shop identity. Automation stays held without verification.',
                      ),
                      trailing: const Icon(Icons.verified_user_outlined),
                      onTap: () async {
                        try {
                          final shop = await AgentChannel.inspectTerminalShop();
                          if (!context.mounted) return;
                          await showDialog<void>(
                            context: context,
                            builder: (c) => AlertDialog(
                              title: Text(
                                shop['shop']?.toString() ?? 'Terminal shop',
                              ),
                              content: Text(
                                'Verified shop: ${shop['scope']}\n${shop['listingCount']} active listings returned.\n\n${(shop['sampleTitles'] as List? ?? []).join('\n')}\n\nRead-only check. No post was sent.',
                              ),
                              actions: [
                                TextButton(
                                  onPressed: () => Navigator.pop(c),
                                  child: const Text('Close'),
                                ),
                              ],
                            ),
                          );
                        } on PlatformException catch (error) {
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(
                                  error.message ??
                                      'Shop identity could not be verified.',
                                ),
                              ),
                            );
                          }
                        }
                      },
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Choose Soko working folder'),
                      subtitle: const Text(
                        'Choose a folder, then open the same folder from Soko Terminal when importing media.',
                      ),
                      trailing: const Icon(Icons.folder_open),
                      onTap: () async {
                        try {
                          final chosen =
                              await AgentChannel.chooseSokoSharedFolder();
                          if (context.mounted && chosen) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                content: Text('Working folder saved.'),
                              ),
                            );
                          }
                        } on PlatformException catch (e) {
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(
                                  e.message ?? 'Folder access failed.',
                                ),
                              ),
                            );
                          }
                        }
                      },
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text(
                        'Export prepared media to working folder',
                      ),
                      subtitle: const Text(
                        'Copies saved photos and videos. Existing exports are skipped.',
                      ),
                      trailing: const Icon(Icons.file_upload_outlined),
                      onTap: () async {
                        try {
                          final count =
                              await AgentChannel.exportSokoSharedMedia();
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text('$count media files exported.'),
                              ),
                            );
                          }
                        } on PlatformException catch (e) {
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(e.message ?? 'Export failed.'),
                              ),
                            );
                          }
                        }
                      },
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Soko Terminal PIN'),
                      subtitle: Text(
                        _settings['sokoPinStored'] == true
                            ? 'Stored securely on this phone'
                            : 'Not configured or locked — enter a working PIN',
                      ),
                      trailing: const Icon(Icons.lock_outline),
                      onTap: _setSokoPin,
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text('Tiktok'),
                  children: [
                    const ListTile(
                      title: Text('Reusable ad studio'),
                      subtitle: Text(
                        'Videos for galleries and services, designed photos for single items and groups. Saved artwork is reused.',
                      ),
                    ),
                    _buildToggleRow(
                      'Reply to questions on my ads',
                      _settings['tikTokNotificationRepliesEnabled'] == true,
                      (v) =>
                          _updateSetting('tikTokNotificationRepliesEnabled', v),
                    ),
                    ListTile(
                      title: const Text('Comment notification inbox'),
                      subtitle: Text(
                        _settings['tiktokCommentInbox'] as String? ??
                            'Loading…',
                      ),
                    ),

                    ListTile(
                      title: const Text('Public ad WhatsApp'),
                      subtitle: Text(
                        (_settings['publicAdWhatsApp'] as String?)
                                    ?.isNotEmpty ==
                                true
                            ? _settings['publicAdWhatsApp'] as String
                            : 'Use item contact or Soko page',
                      ),
                      trailing: const Icon(Icons.edit_outlined),
                      onTap: _setPublicAdWhatsApp,
                    ),
                    _buildToggleRow(
                      'Enable TikTok posting',
                      _settings['tikTokEnabled'] == true,
                      (v) => _updateSetting('tikTokEnabled', v),
                    ),
                    _buildToggleRow(
                      'Share ads to TikTok Stories (up to 6/day)',
                      _settings['tikTokStoriesEnabled'] == true,
                      (v) => _updateSetting('tikTokStoriesEnabled', v),
                    ),
                    _buildToggleRow(
                      'Run selected cadence all day',
                      _settings['tikTokAlwaysOn'] == true,
                      (v) => _updateSetting('tikTokAlwaysOn', v),
                    ),
                    _buildToggleRow(
                      'Relevant public comments (up to 12 per day)',
                      _settings['tikTokSocialEnabled'] == true,
                      (v) => _updateSetting('tikTokSocialEnabled', v),
                    ),
                    _buildToggleRow(
                      'Monitor comments and analytics',
                      _settings['tikTokCommentsEnabled'] == true,
                      (v) => _updateSetting('tikTokCommentsEnabled', v),
                    ),
                    _buildIntervalRow(
                      'Posting interval',
                      _settings['tikTokIntervalMinutes'] ?? 10,
                      [10, 30, 60, 120, 240, 480],
                      (v) => _updateSetting('tikTokIntervalMinutes', v),
                    ),
                    _buildSliderRow(
                      'Daily cap',
                      (_settings['tikTokDailyCap'] ?? 10).toDouble(),
                      1,
                      144,
                      (v) => _updateSetting('tikTokDailyCap', v.toInt()),
                    ),
                    _moduleStats('TikTok'),
                    const SizedBox(height: 24),
                  ],
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: const Text('YouTube Shorts'),
                  children: [
                    _buildToggleRow(
                      'Enable YouTube Shorts',
                      _settings['youtubeEnabled'] == true,
                      (v) => _updateSetting('youtubeEnabled', v),
                    ),
                    ListTile(
                      title: const Text('Destination channel'),
                      subtitle: Text(
                        (_settings['youtubeChannel'] as String?)?.isNotEmpty ==
                                true
                            ? _settings['youtubeChannel'] as String
                            : 'Choose your @channel',
                      ),
                      trailing: const Icon(Icons.edit_outlined),
                      onTap: _setYouTubeChannel,
                    ),
                    const ListTile(
                      title: Text('Reuse TikTok video and sound'),
                      subtitle: Text(
                        'Only verified TikTok ads are eligible. The finished video keeps its soundtrack.',
                      ),
                    ),
                    _buildToggleRow(
                      'Our soundtracks are cleared for YouTube',
                      _settings['youtubeAudioCleared'] == true,
                      (v) => _updateSetting('youtubeAudioCleared', v),
                    ),
                    ListTile(
                      title: const Text('Shorts visibility'),
                      trailing: DropdownButton<String>(
                        value:
                            _settings['youtubeVisibility'] as String? ??
                            'Public',
                        items: const ['Public', 'Unlisted', 'Private']
                            .map(
                              (value) => DropdownMenuItem(
                                value: value,
                                child: Text(value),
                              ),
                            )
                            .toList(),
                        onChanged: (value) {
                          if (value != null)
                            _updateSetting('youtubeVisibility', value);
                        },
                      ),
                    ),
                    _buildToggleRow(
                      'Made for kids',
                      _settings['youtubeMadeForKids'] == true,
                      (v) => _updateSetting('youtubeMadeForKids', v),
                    ),
                    _buildIntervalRow(
                      'Posting interval',
                      _settings['youtubeIntervalMinutes'] ?? 240,
                      [10, 30, 60, 120, 240, 480, 1440],
                      (v) => _updateSetting('youtubeIntervalMinutes', v),
                    ),
                    _buildSliderRow(
                      'Daily Shorts cap',
                      (_settings['youtubeDailyCap'] ?? 3).toDouble(),
                      1,
                      24,
                      (v) => _updateSetting('youtubeDailyCap', v.toInt()),
                    ),
                    ListTile(
                      title: const Text('Status'),
                      subtitle: Text(
                        _settings['youtubeStatus'] as String? ??
                            'Not yet checked',
                      ),
                    ),
                    ListTile(
                      title: const Text('Cross-post queue'),
                      subtitle: Text(
                        _settings['youtubeQueue'] as String? ?? 'No queued ads',
                      ),
                    ),
                    ListTile(
                      title: const Text('Check preparation without uploading'),
                      subtitle: const Text(
                        'Amara prepares the latest verified TikTok ad herself and checks its sound, channel and details.',
                      ),
                      trailing: const Icon(Icons.fact_check_outlined),
                      onTap: () async {
                        try {
                          final message = await const MethodChannel(
                            'com.sanaa.agent/core',
                          ).invokeMethod<String>('checkYouTubePreparation');
                          if (mounted)
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(message ?? 'Check queued'),
                              ),
                            );
                        } on PlatformException catch (e) {
                          if (mounted)
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(
                                  e.message ?? 'Could not queue check',
                                ),
                              ),
                            );
                        }
                      },
                    ),
                    _moduleStats('YouTube'),
                  ],
                ),
                ExpansionTile(
                  title: const Text('Module activity and statistics'),
                  children: [
                    for (final module in [
                      'TikTok',
                      'YouTube',
                      'WhatsApp',
                      'Soko',
                      'Market',
                      'Doctor',
                      'Memory',
                      'General',
                    ])
                      _moduleStats(module),
                  ],
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text('Whatsapp Autopilot'),
                  children: [
                    _buildToggleRow(
                      'Autopilot',
                      _settings['whatsAppEnabled'] == true,
                      (v) => _updateSetting('whatsAppEnabled', v),
                    ),
                    const Text(
                      'Replies and follow-ups for approved contacts.',
                      style: TextStyle(
                        color: Colors.white54,
                        fontSize: 12,
                        height: 1.4,
                      ),
                    ),
                    _buildToggleRow(
                      'Customer replies',
                      _settings['whatsAppInboundEnabled'] == true,
                      (v) => _updateSetting('whatsAppInboundEnabled', v),
                    ),
                    _buildToggleRow(
                      'Follow-ups',
                      _settings['whatsAppFollowUpsEnabled'] == true,
                      (v) => _updateSetting('whatsAppFollowUpsEnabled', v),
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.groups_outlined),
                      title: const Text('WhatsApp groups'),
                      subtitle: const Text(
                        'Permissions, schedules and delivery status',
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => const WhatsAppGroupsScreen(),
                        ),
                      ),
                    ),
                    _buildToggleRow(
                      'Group replies & ads',
                      _settings['whatsAppGroupsEnabled'] == true,
                      (v) => _updateSetting('whatsAppGroupsEnabled', v),
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.support_agent),
                      title: const Text('Manager WhatsApp'),
                      subtitle: Text(
                        (_settings['managerWhatsApp'] as String?)?.isNotEmpty ==
                                true
                            ? _settings['managerWhatsApp'] as String
                            : 'Inquiries and order updates',
                      ),
                      onTap: () => _setPublicAdWhatsApp(manager: true),
                    ),
                    _buildToggleRow(
                      '24/7 inbound cover',
                      _settings['whatsAppAlwaysOn'] == true,
                      (v) => _updateSetting('whatsAppAlwaysOn', v),
                    ),
                    _buildSliderRow(
                      'Follow-up days',
                      (_settings['whatsAppFollowUpDays'] ?? 7).toDouble(),
                      1,
                      30,
                      (v) => _updateSetting('whatsAppFollowUpDays', v.toInt()),
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(
                        Icons.contacts,
                        color: Color(0xFF50E3C2),
                      ),
                      title: const Text('Contact control'),
                      subtitle: const Text('Manage contact permissions.'),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => const ContactPermissionsScreen(),
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text('Memory & Recovery'),
                  children: [
                    _buildToggleRow(
                      'Back up relationship memory',
                      _settings['memoryBackupEnabled'] == true,
                      (v) => _updateSetting('memoryBackupEnabled', v),
                    ),
                    _buildToggleRow(
                      'Merge memory after reinstall',
                      _settings['memoryAutoRestoreEnabled'] == true,
                      (v) => _updateSetting('memoryAutoRestoreEnabled', v),
                    ),
                    const Text(
                      'Only compact customer summaries, catalogue knowledge and learning evidence are stored on cards.sanaa.ug. Raw WhatsApp transcripts and API secrets stay on the phone.',
                      style: TextStyle(
                        color: Colors.white38,
                        fontSize: 11,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 10),
                    Wrap(
                      spacing: 8,
                      children: [
                        OutlinedButton.icon(
                          onPressed: _settings['memoryBackupEnabled'] == true
                              ? () => _memoryAction(false)
                              : null,
                          icon: const Icon(Icons.cloud_upload_outlined),
                          label: const Text('Back up now'),
                        ),
                        OutlinedButton.icon(
                          onPressed: _settings['memoryBackupEnabled'] == true
                              ? () => _memoryAction(true)
                              : null,
                          icon: const Icon(Icons.cloud_download_outlined),
                          label: const Text('Restore'),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text('Market Intelligence'),
                  children: [
                    _buildToggleRow(
                      'Jiji scraping',
                      _settings['jijiScrapingEnabled'] == true,
                      (v) => _updateSetting('jijiScrapingEnabled', v),
                    ),
                    _buildToggleRow(
                      'Jumia intelligence',
                      _settings['jumiaIntelligenceEnabled'] == true,
                      (v) => _updateSetting('jumiaIntelligenceEnabled', v),
                    ),
                    _buildToggleRow(
                      'Allow screenshots for Groq vision',
                      _settings['visionConsent'] == true,
                      (v) => _updateSetting('visionConsent', v),
                    ),
                    const Text(
                      'Jumia has no usable accessibility text on this phone. This separate consent lets Amara send only its public storefront screenshots to the configured vision model.',
                      style: TextStyle(color: Colors.white38, fontSize: 11),
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Groq vision model'),
                      subtitle: Text(
                        (_settings['groqVisionModel']?.toString().isNotEmpty ??
                                false)
                            ? _settings['groqVisionModel'].toString()
                            : 'Not configured',
                      ),
                      trailing: const Icon(Icons.edit_outlined),
                      onTap: _setVisionModel,
                    ),
                    _buildSliderRow(
                      'Scrape interval (hours)',
                      (_settings['jijiScrapeIntervalHours'] ?? 4).toDouble(),
                      1,
                      24,
                      (v) =>
                          _updateSetting('jijiScrapeIntervalHours', v.toInt()),
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text('General'),
                  children: [
                    _buildSliderRow(
                      'Failure cooldown cap (minutes; 0 = off)',
                      (_settings['maxRetryCooldownMinutes'] ?? 1440).toDouble(),
                      0,
                      1440,
                      (v) =>
                          _updateSetting('maxRetryCooldownMinutes', v.toInt()),
                    ),
                    const Text(
                      'Limits failure cooldowns. Delivery and device protections stay on.',
                    ),
                    _buildToggleRow(
                      'Morning broadcast',
                      _settings['morningBroadcastEnabled'] == true,
                      (v) => _updateSetting('morningBroadcastEnabled', v),
                    ),
                    _buildSliderRow(
                      'Max screen time (min/day)',
                      (_settings['maxScreenMinutesPerDay'] ?? 90).toDouble(),
                      10,
                      1440,
                      (v) =>
                          _updateSetting('maxScreenMinutesPerDay', v.toInt()),
                    ),
                    _buildTimeRow(
                      'Quiet hours start',
                      _settings['quietHoursStart']?.toString() ?? '22:00',
                      (v) => _updateSetting('quietHoursStart', v),
                    ),
                    _buildTimeRow(
                      'Quiet hours end',
                      _settings['quietHoursEnd']?.toString() ?? '08:00',
                      (v) => _updateSetting('quietHoursEnd', v),
                    ),
                    _buildToggleRow(
                      'Nightly Doctor cleanup during quiet hours',
                      _settings['nightlyDoctorEnabled'] == true,
                      (v) => _updateSetting('nightlyDoctorEnabled', v),
                    ),
                    const Text(
                      'Learns from blockers, compacts stale queue entries, and clears only orphaned waits. It never retries uncertain sends or posts.',
                    ),
                  ],
                ),
              ],
            ),
    );
  }

  Widget _buildSectionHeader(String title) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Text(
      title,
      style: const TextStyle(
        color: Colors.white38,
        fontSize: 11,
        letterSpacing: 2,
        fontWeight: FontWeight.w700,
      ),
    ),
  );

  Widget _buildToggleRow(
    String label,
    bool value,
    ValueChanged<bool> onChanged,
  ) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Expanded(child: Text(label, style: const TextStyle(fontSize: 15))),
          Switch(
            value: value,
            onChanged: onChanged,
            activeThumbColor: const Color(0xFF50E3C2),
          ),
        ],
      ),
    );
  }

  Widget _buildIntervalRow(
    String label,
    dynamic current,
    List<int> options,
    ValueChanged<int> onChanged,
  ) {
    final currentVal = (current as num).toInt();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Expanded(child: Text(label, style: const TextStyle(fontSize: 15))),
          DropdownButton<int>(
            value: currentVal,
            dropdownColor: const Color(0xFF121616),
            items: options
                .map(
                  (o) => DropdownMenuItem(
                    value: o,
                    child: Text(o < 60 ? '$o min' : '${o ~/ 60} hr'),
                  ),
                )
                .toList(),
            onChanged: (v) => v != null ? onChanged(v) : null,
          ),
        ],
      ),
    );
  }

  Widget _buildSliderRow(
    String label,
    double value,
    double min,
    double max,
    ValueChanged<double> onChanged,
  ) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(label, style: const TextStyle(fontSize: 15)),
              ),
              Text(
                value.toInt().toString(),
                style: const TextStyle(
                  color: Color(0xFF50E3C2),
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
          Slider(
            value: value,
            min: min,
            max: max,
            divisions: (max - min).toInt(),
            onChanged: onChanged,
            activeColor: const Color(0xFF50E3C2),
          ),
        ],
      ),
    );
  }

  Widget _buildTimeRow(
    String label,
    String value,
    ValueChanged<String> onChanged,
  ) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Expanded(child: Text(label, style: const TextStyle(fontSize: 15))),
          GestureDetector(
            onTap: () async {
              final time = await showTimePicker(
                context: context,
                initialTime: TimeOfDay.now(),
              );
              if (time != null) {
                onChanged(
                  '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}',
                );
              }
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: const Color(0xFF121616),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                value,
                style: const TextStyle(color: Color(0xFF50E3C2)),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
