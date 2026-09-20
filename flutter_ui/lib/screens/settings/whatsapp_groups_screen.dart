import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class WhatsAppGroupsScreen extends StatefulWidget {
  const WhatsAppGroupsScreen({super.key});
  @override
  State<WhatsAppGroupsScreen> createState() => _WhatsAppGroupsScreenState();
}

class _WhatsAppGroupsScreenState extends State<WhatsAppGroupsScreen> {
  static const channel = MethodChannel('com.sanaa.agent/core');
  List<Map<String, dynamic>> groups = [];
  bool loading = true;
  String? error;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final rows =
          await channel.invokeMethod<List<dynamic>>('whatsappGroupSettings') ??
          [];
      if (mounted) {
        setState(() {
          groups = rows
              .map((r) => Map<String, dynamic>.from(r as Map))
              .toList();
          loading = false;
          error = null;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          error = 'Could not load groups. Try again.';
          loading = false;
        });
      }
    }
  }

  Future<void> editProfile(
    Map<String, dynamic> group,
    String field,
    String label,
    String hint,
  ) async {
    final controller = TextEditingController(
      text: group[field] as String? ?? '',
    );
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(label),
        content: TextField(
          controller: controller,
          maxLength: 1000,
          maxLines: 4,
          decoration: InputDecoration(helperText: hint),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (value != null && mounted) await update(group, field, value);
  }

  Future<void> update(
    Map<String, dynamic> group,
    String field,
    dynamic value,
  ) async {
    try {
      await channel.invokeMethod('updateWhatsappGroup', {
        'id': group['id'],
        'field': field,
        'value': value,
      });
      await load();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Setting could not be saved.')),
        );
      }
    }
  }

  Future<void> openGroup(String id) async {
    try {
      final opened = await channel.invokeMethod<bool>('openWhatsappGroup', {
        'id': id,
      });
      if (opened != true && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'A fresh notification from this group is needed to open it safely.',
            ),
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not open this group.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('WhatsApp groups'),
      actions: [IconButton(onPressed: load, icon: const Icon(Icons.refresh))],
    ),
    body: loading
        ? const Center(child: CircularProgressIndicator())
        : RefreshIndicator(
            onRefresh: load,
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                const Text('Choose which groups Amara manages.'),

                if (error != null)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(error!),
                  ),
                if (groups.isEmpty)
                  const Padding(
                    padding: EdgeInsets.all(24),
                    child: Text(
                      'No groups yet. Receive a group notification or add one in Contacts.',
                    ),
                  ),
                ...groups.map((g) {
                  final id = g['id'] as String;
                  final interval =
                      (g['intervalMinutes'] as num?)?.toInt() ?? 1440;
                  final options = <int>{
                    60,
                    120,
                    240,
                    480,
                    1440,
                    10080,
                    interval,
                  }.toList()..sort();
                  return Card(
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            g['name'] as String,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          Text(
                            'Identity …${id.length > 8 ? id.substring(id.length - 8) : id} · ${g['originVerified'] == true ? 'Observed from WhatsApp' : 'Contact directory'}',
                          ),
                          TextButton(
                            onPressed: () => editProfile(g, 'name', 'Correct saved group name', 'Copy the complete name from WhatsApp. The next check verifies access.'),
                            child: const Text('Correct saved name'),
                          ),
                          TextButton.icon(
                            onPressed: () => openGroup(id),
                            icon: const Icon(Icons.open_in_new),
                            label: const Text('Open group'),
                          ),
                          Text(
                            'Last promotion: ${g['lastOutcome'] ?? 'No scheduled outcome yet'}',
                          ),
                          if ((g['lastPromotionAt'] as num? ?? 0) > 0)
                            Text(
                              'Last confirmed: ${DateTime.fromMillisecondsSinceEpoch((g['lastPromotionAt'] as num).toInt()).toLocal()}',
                            ),
                          if ((g['lastReason'] as String? ?? '').isNotEmpty)
                            Text('Details: ${g['lastReason']}'),
                          TextButton.icon(
                            onPressed: () async {
                              try {
                                await channel.invokeMethod(
                                  'checkWhatsappGroup',
                                  {'id': id},
                                );
                                if (!context.mounted) return;
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(
                                    content: Text(
                                      'Read-only group check queued. Refresh for its result.',
                                    ),
                                  ),
                                );
                              } catch (_) {
                                if (context.mounted) {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    const SnackBar(
                                      content: Text(
                                        'Could not queue the check. Review group permission.',
                                      ),
                                    ),
                                  );
                                }
                              }
                            },
                            icon: const Icon(Icons.fact_check_outlined),
                            label: const Text('Check exact group · no send'),
                          ),
                          if ((g['lastCheck']?.toString() ?? '').isNotEmpty)
                            Text('Latest check: ${g['lastCheck']}'),
                          if (g['paused'] == true) ...[
                            const Text(
                              'Ads held · automatic verification every 30 minutes.',
                            ),
                            TextButton(
                              onPressed: g['promote'] == true
                                  ? () => update(g, 'resume', true)
                                  : null,
                              child: const Text('Resume ads'),
                            ),
                          ] else if ((g['nextPromotionAt'] as num? ?? 0) > 0)
                            Text(
                              (g['nextPromotionAt'] as num).toInt() <=
                                      DateTime.now().millisecondsSinceEpoch
                                  ? 'Due · awaiting scheduler'
                                  : 'Next: ${DateTime.fromMillisecondsSinceEpoch((g['nextPromotionAt'] as num).toInt()).toLocal()}',
                            ),
                          ExpansionTile(
                            tilePadding: EdgeInsets.zero,
                            title: const Text('Topics & rules'),
                            children: [
                              for (final field in const {
                                'purpose': 'Group purpose',
                                'rules': 'Participation rules',
                                'offerKeywords': 'Offer topics',
                                'replyKeywords': 'Reply topics',
                              }.entries)
                                ListTile(
                                  contentPadding: EdgeInsets.zero,
                                  title: Text(field.value),
                                  subtitle: Text(
                                    (g[field.key] as String? ?? '').isEmpty
                                        ? 'Not set'
                                        : g[field.key] as String,
                                  ),
                                  trailing: const Icon(Icons.edit_outlined),
                                  onTap: () => editProfile(
                                    g,
                                    field.key,
                                    field.value,
                                    field.key.endsWith('Keywords')
                                        ? 'Comma-separated topics. Blank allows all topics.'
                                        : 'Owner guidance for this group only.',
                                  ),
                                ),
                            ],
                          ),
                          SwitchListTile(
                            contentPadding: EdgeInsets.zero,
                            title: const Text('Listen'),
                            subtitle: const Text('Read new messages'),
                            value: g['listen'] == true,
                            onChanged: (v) => update(g, 'listen', v),
                          ),
                          SwitchListTile(
                            contentPadding: EdgeInsets.zero,
                            title: const Text('Reply'),
                            subtitle: const Text('Answer relevant questions'),
                            value: g['reply'] == true,
                            onChanged: g['listen'] == true
                                ? (v) => update(g, 'reply', v)
                                : null,
                          ),
                          SwitchListTile(
                            contentPadding: EdgeInsets.zero,
                            title: const Text('Post ads'),
                            subtitle: const Text(
                              'Designed product and service posters',
                            ),
                            value: g['promote'] == true,
                            onChanged: (v) => update(g, 'promote', v),
                          ),
                          DropdownButtonFormField<int>(
                            initialValue: interval,
                            decoration: const InputDecoration(
                              labelText: 'Promotion interval',
                            ),
                            items: options
                                .map(
                                  (v) => DropdownMenuItem(
                                    value: v,
                                    child: Text(
                                      v == 10080
                                          ? 'Weekly'
                                          : v == 1440
                                          ? 'Daily'
                                          : 'Every ${v ~/ 60} hour(s)',
                                    ),
                                  ),
                                )
                                .toList(),
                            onChanged: g['promote'] == true
                                ? (v) {
                                    if (v != null) {
                                      update(g, 'intervalMinutes', v);
                                    }
                                  }
                                : null,
                          ),
                          if (g['promote'] == true &&
                              g['promotionsReady'] != true)
                            const Padding(
                              padding: EdgeInsets.only(top: 8),
                              child: Text(
                                'Check group identity before posting ads.',
                              ),
                            ),
                        ],
                      ),
                    ),
                  );
                }),
              ],
            ),
          ),
  );
}
