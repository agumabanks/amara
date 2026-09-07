import 'dart:async';
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

  Future<void> _updateSetting(String key, dynamic value) async {
    await AgentChannel.setAmaraSetting(key, value);
    _loadSettings();
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
          decoration: const InputDecoration(
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
                _buildSectionHeader('SOKO ACCESS'),
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
                _buildSectionHeader('TIKTOK'),
                _buildToggleRow(
                  'Enable TikTok posting',
                  _settings['tikTokEnabled'] == true,
                  (v) => _updateSetting('tikTokEnabled', v),
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
                const SizedBox(height: 24),
                _buildSectionHeader('WHATSAPP AUTOPILOT'),
                _buildToggleRow(
                  'Master autopilot',
                  _settings['whatsAppEnabled'] == true,
                  (v) => _updateSetting('whatsAppEnabled', v),
                ),
                const Text(
                  'When on, Amara can handle authorized WhatsApp work without asking for every message. Turning this off stops new autonomous replies and follow-ups.',
                  style: TextStyle(
                    color: Colors.white54,
                    fontSize: 12,
                    height: 1.4,
                  ),
                ),
                _buildToggleRow(
                  'Reply to incoming messages',
                  _settings['whatsAppInboundEnabled'] == true,
                  (v) => _updateSetting('whatsAppInboundEnabled', v),
                ),
                _buildToggleRow(
                  'Proactive customer follow-ups',
                  _settings['whatsAppFollowUpsEnabled'] == true,
                  (v) => _updateSetting('whatsAppFollowUpsEnabled', v),
                ),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.groups_outlined),
                  title: const Text('WhatsApp groups'),
                  subtitle: const Text(
                    'Listening, replies and promotion schedules',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => const WhatsAppGroupsScreen(),
                    ),
                  ),
                ),
                _buildToggleRow(
                  'Reply in authorized groups',
                  _settings['whatsAppGroupsEnabled'] == true,
                  (v) => _updateSetting('whatsAppGroupsEnabled', v),
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
                  leading: const Icon(Icons.contacts, color: Color(0xFF50E3C2)),
                  title: const Text('Contact control'),
                  subtitle: const Text(
                    'Choose who Amara may watch, reply to, send to, or fully manage.',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => const ContactPermissionsScreen(),
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                _buildSectionHeader('MEMORY & RECOVERY'),
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
                _buildSectionHeader('MARKET INTELLIGENCE'),
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
                  (v) => _updateSetting('jijiScrapeIntervalHours', v.toInt()),
                ),
                const SizedBox(height: 24),
                _buildSectionHeader('GENERAL'),
                _buildSliderRow(
                  'Failure cooldown cap (minutes; 0 = off)',
                  (_settings['maxRetryCooldownMinutes'] ?? 1440).toDouble(),
                  0,
                  1440,
                  (v) => _updateSetting('maxRetryCooldownMinutes', v.toInt()),
                ),
                const Text(
                  'Only controls task-failure circuit breakers. Duplicate protection, retry backoff, quiet hours and device-health limits still apply.',
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
                  (v) => _updateSetting('maxScreenMinutesPerDay', v.toInt()),
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
