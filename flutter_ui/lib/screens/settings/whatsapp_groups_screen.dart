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
                const Text(
                  'Choose where Amara listens, replies and promotes your catalogue. Global WhatsApp controls still apply.',
                ),
                const SizedBox(height: 12),
                const Text(
                  'Promotions rotate products and services with photos and details. Posting runs between 8 a.m. and 8 p.m., subject to consent, daily limits and verified delivery.',
                ),
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(error!),
                  ),
                if (groups.isEmpty)
                  const Padding(
                    padding: EdgeInsets.all(24),
                    child: Text(
                      'Groups appear after a WhatsApp notification or after you add them in Contacts. Newly observed groups need your permission before replies or ads.',
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
                          TextButton.icon(
                            onPressed: () => openGroup(id),
                            icon: const Icon(Icons.open_in_new),
                            label: const Text('Open group to identify it'),
                          ),
                          Text(
                            'Last promotion: ${g['lastOutcome'] ?? 'No scheduled outcome yet'}',
                          ),
                          if ((g['lastReason'] as String? ?? '').isNotEmpty)
                            Text('Details: ${g['lastReason']}'),
                          if (g['paused'] == true) ...[
                            const Text(
                              'Promotions paused after a delivery blocker. Check the group destination and posting permission before resuming.',
                            ),
                            TextButton(
                              onPressed: g['promote'] == true
                                  ? () => update(g, 'resume', true)
                                  : null,
                              child: const Text(
                                'Resume promotions after checking',
                              ),
                            ),
                          ] else if ((g['nextPromotionAt'] as num? ?? 0) > 0)
                            Text(
                              'Next eligible: ${DateTime.fromMillisecondsSinceEpoch((g['nextPromotionAt'] as num).toInt()).toLocal()}',
                            ),
                          SwitchListTile(
                            contentPadding: EdgeInsets.zero,
                            title: const Text('Listen'),
                            subtitle: const Text(
                              'Watch incoming group messages',
                            ),
                            value: g['listen'] == true,
                            onChanged: (v) => update(g, 'listen', v),
                          ),
                          SwitchListTile(
                            contentPadding: EdgeInsets.zero,
                            title: const Text('Reply'),
                            subtitle: const Text(
                              'Respond to incoming messages in this group',
                            ),
                            value: g['reply'] == true,
                            onChanged: g['listen'] == true
                                ? (v) => update(g, 'reply', v)
                                : null,
                          ),
                          SwitchListTile(
                            contentPadding: EdgeInsets.zero,
                            title: const Text('Scheduled promotions'),
                            subtitle: const Text(
                              'Authorize product and service promotions here',
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
                                'Promotions are held until the destination can be selected uniquely. Amara will not choose between groups with the same name.',
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
