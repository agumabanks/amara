import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../bridge/agent_channel.dart';
import '../settings/settings_screen.dart';
import '../settings/whatsapp_groups_screen.dart';
import '../work/work_screen.dart';
import '../commercial/commercial_screen.dart';

class DoctorScreen extends StatefulWidget {
  const DoctorScreen({super.key});
  @override
  State<DoctorScreen> createState() => _DoctorScreenState();
}

class _DoctorScreenState extends State<DoctorScreen>
    with WidgetsBindingObserver {
  static const channel = MethodChannel('com.sanaa.agent/core');
  Map<String, dynamic> data = {};
  bool busy = false;
  bool reviewingMedia = false;
  String? error;
  String? summary;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) load();
  }

  Future<void> load({bool repair = false}) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      if (repair) {
        final result = await AgentChannel.runOperationalHealth();
        summary = result['summary']?.toString();
      }
      final value = await channel.invokeMapMethod<String, dynamic>(
        'doctorStatus',
      );
      if (mounted) setState(() => data = value ?? {});
    } catch (e) {
      if (mounted) {
        setState(
          () => error =
              'Check could not finish. ${e is PlatformException ? e.message : e}',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  List<Map> rows(String key) =>
      (data[key] as List? ?? []).whereType<Map>().toList();
  Future<void> open(Widget page) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => page));
    if (mounted) await load();
  }

  Future<void> deepRepairGroups() async {
    setState(() => busy = true);
    try {
      final result = await channel.invokeMethod<Map>(
        'deepRepairWhatsAppGroups',
      );
      if (mounted) {
        setState(
          () => summary =
              result?['summary']?.toString() ?? 'Group repair requested.',
        );
        setState(() => busy = false);
        await load();
      }
    } catch (e) {
      if (mounted) setState(() => error = 'Group repair could not start. $e');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> closeAllReviewHolds() async {
    final disposition = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Close all review holds?'),
        content: const Text(
          'This closes only unclaimed review holds as owner-reviewed. It does not claim that any message was delivered, and the history remains available.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, 'no_longer_needed'),
            child: const Text('Close holds'),
          ),
        ],
      ),
    );
    if (disposition == null || !mounted) return;
    setState(() => busy = true);
    try {
      final count =
          await channel.invokeMethod<int>('closeAllReviewHolds', {
            'disposition': disposition,
          }) ??
          0;
      if (mounted) {
        setState(
          () => summary =
              '$count review hold(s) closed. History retained; no delivery claimed.',
        );
        setState(() => busy = false);
        await load();
      }
    } catch (e) {
      if (mounted) {
        setState(() => error = 'Review holds could not be closed. $e');
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  String mediaSize(num bytes) =>
      '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MiB';

  Future<void> reviewMediaCleanup() async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final preview =
          await channel.invokeMapMethod<String, dynamic>(
            'previewMediaCleanup',
          ) ??
          {};
      if (!mounted) return;
      final stores = (preview['stores'] as List? ?? [])
          .whereType<Map>()
          .toList();
      final candidates = (preview['candidates'] as List? ?? [])
          .whereType<Map>()
          .where((item) => item['id'] is String)
          .toList();
      final selected = <String>{};
      setState(() => reviewingMedia = true);
      final approved = await showDialog<List<String>>(
        context: context,
        builder: (dialogContext) => StatefulBuilder(
          builder: (context, update) => AlertDialog(
            title: const Text('Review obsolete media'),
            content: SizedBox(
              width: double.maxFinite,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Only selected obsolete media will be removed. Active and uncertain publication evidence stays protected. Publication history and binding records remain; this does not retry or publish anything.',
                    ),
                    const SizedBox(height: 12),
                    for (final store in stores)
                      Text(
                        '${store['name']}: ${mediaSize(store['usedBytes'] as num? ?? 0)} / ${mediaSize(store['limitBytes'] as num? ?? 0)}',
                      ),
                    const SizedBox(height: 12),
                    if (candidates.isEmpty)
                      const Text(
                        'Nothing safely reclaimable. Protected evidence cannot be removed here.',
                      ),
                    for (final candidate in candidates)
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(
                          '${candidate['store']} · ${mediaSize(candidate['bytes'] as num? ?? 0)}',
                        ),
                        subtitle: Text(
                          [
                            if (candidate['label'] is String)
                              candidate['label'] as String,
                            if (candidate['status'] is String)
                              candidate['status'] as String,
                            candidate['id'] as String,
                          ].join('\n'),
                        ),
                        value: selected.contains(candidate['id']),
                        onChanged: (value) => update(() {
                          if (value == true) {
                            selected.add(candidate['id'] as String);
                          } else {
                            selected.remove(candidate['id']);
                          }
                        }),
                      ),
                  ],
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: selected.isEmpty
                    ? null
                    : () => Navigator.pop(dialogContext, selected.toList()),
                child: const Text('Remove selected media'),
              ),
            ],
          ),
        ),
      );
      if (mounted) setState(() => reviewingMedia = false);
      if (approved == null || approved.isEmpty || !mounted) return;
      final result =
          await channel.invokeMapMethod<String, dynamic>(
            'confirmMediaCleanup',
            {'candidateIds': approved},
          ) ??
          {};
      if (!mounted) return;
      setState(
        () => summary =
            'Removed ${result['removedCount'] ?? 0} obsolete media file(s); reclaimed ${mediaSize(result['reclaimedBytes'] as num? ?? 0)}. Changed or protected items were retained.',
      );
      try {
        final refreshed = await channel.invokeMapMethod<String, dynamic>(
          'doctorStatus',
        );
        if (mounted) setState(() => data = refreshed ?? {});
      } catch (_) {
        if (mounted) {
          setState(
            () => error =
                'Cleanup completed, but health status could not refresh. The removal result is saved in Latest check details.',
          );
        }
      }
    } catch (e) {
      if (mounted) setState(() => error = 'Media review could not finish. $e');
    } finally {
      if (mounted) {
        setState(() {
          busy = false;
          reviewingMedia = false;
        });
      }
    }
  }

  Widget action(String reason) {
    final text = reason.toLowerCase();
    if (text.contains('media evidence store full')) {
      return TextButton(
        onPressed: busy ? null : reviewMediaCleanup,
        child: const Text('Review retained media'),
      );
    }
    if (text.contains('timezone') || text.contains('commercial policy')) {
      return TextButton(
        onPressed: () => open(const CommercialScreen()),
        child: const Text('Open commercial policy'),
      );
    }
    if (text.contains('accessibility') || text.contains('phone control')) {
      return TextButton(
        onPressed: () => AgentChannel.openAccessibilitySettings(),
        child: const Text('Open phone access'),
      );
    }
    if (text.contains('group') || text.contains('recipient')) {
      return TextButton(
        onPressed: () => open(const WhatsAppGroupsScreen()),
        child: const Text('Check group access'),
      );
    }
    if (text.contains('budget') ||
        text.contains('reserve') ||
        text.contains('shop') ||
        text.contains('credential') ||
        text.contains('notification') ||
        text.contains('overlay') ||
        text.contains('optimization') ||
        text.contains('off by owner')) {
      return TextButton(
        onPressed: () => open(const SettingsScreen()),
        child: const Text('Open relevant settings'),
      );
    }
    return TextButton(
      onPressed: () => open(const WorkScreen()),
      child: const Text('Review affected work'),
    );
  }

  @override
  Widget build(BuildContext context) {
    final health = data['health'] as Map? ?? {};
    final operational = (health['blockers'] as List? ?? [])
        .map((e) => e.toString())
        .toList();
    final issues = rows('issues');
    final resolved = rows('resolved');
    return Scaffold(
      appBar: AppBar(title: const Text('Amara Doctor')),
      body: RefreshIndicator(
        onRefresh: () => load(),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              error != null
                  ? 'Health check unavailable'
                  : data.isEmpty
                  ? 'Checking current health…'
                  : operational.isEmpty && issues.isEmpty
                  ? 'No current blockers found'
                  : 'Let’s get work moving',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            const Text(
              'Automatic checks are scheduled every 30 minutes while Amara is on. Android may delay background checks.',
            ),
            if ((health['lastCheckAt'] as num? ?? 0) > 0)
              Text(
                'Last checked: ${DateTime.fromMillisecondsSinceEpoch((health['lastCheckAt'] as num).toInt()).toLocal()}',
              ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: busy ? null : () => load(repair: true),
              icon: const Icon(Icons.health_and_safety),
              label: Text(busy ? 'Checking…' : 'Check & repair now'),
            ),
            OutlinedButton.icon(
              onPressed: busy
                  ? null
                  : () async {
                      setState(() => busy = true);
                      try {
                        final result = await channel.invokeMethod<Map>(
                          'runNightlyDoctorNow',
                        );
                        if (mounted) {
                          setState(
                            () => summary = result?['ran'] == true
                                ? 'Nightly cleanup completed: learned ${result?['learned'] ?? 0}, compacted ${result?['compacted'] ?? 0}, cleared ${result?['clearedStaleWaits'] ?? 0} stale waits.'
                                : 'Nightly cleanup did not run: ${result?['reason'] ?? 'check quiet hours and enable it in Settings.'}',
                          );
                          setState(() => busy = false);
                          await load();
                        }
                      } catch (e) {
                        if (mounted) {
                          setState(
                            () => error = 'Nightly cleanup could not run. $e',
                          );
                        }
                      } finally {
                        if (mounted) setState(() => busy = false);
                      }
                    },
              icon: const Icon(Icons.nightlight_round),
              label: const Text('Run quiet-hours cleanup now'),
            ),
            if (issues.any(
              (issue) =>
                  '${issue['task']} ${issue['reason']}'.toLowerCase().contains(
                    'group',
                  ) ||
                  '${issue['task']} ${issue['reason']}'.toLowerCase().contains(
                    'recipient',
                  ),
            ))
              OutlinedButton.icon(
                onPressed: busy ? null : deepRepairGroups,
                icon: const Icon(Icons.auto_fix_high),
                label: const Text('Deep repair WhatsApp groups'),
              ),
            OutlinedButton.icon(
              onPressed: busy ? null : reviewMediaCleanup,
              icon: const Icon(Icons.storage),
              label: const Text('Review obsolete media'),
            ),
            OutlinedButton.icon(
              onPressed: busy ? null : closeAllReviewHolds,
              icon: const Icon(Icons.done_all),
              label: const Text('Close all review holds'),
            ),
            TextButton.icon(
              icon: const Icon(Icons.notifications_paused_outlined),
              label: const Text('Acknowledge current alerts'),
              onPressed: busy
                  ? null
                  : () async {
                      setState(() => busy = true);
                      try {
                        await channel.invokeMethod<bool>(
                          'acknowledgeHealthAlerts',
                        );
                        if (mounted) {
                          setState(
                            () => summary =
                                'Current alerts acknowledged. The issues stay visible until resolved. New conditions can still notify your manager.',
                          );
                        }
                      } catch (_) {
                        if (mounted) {
                          setState(
                            () => error =
                                'Acknowledgement could not be saved. Try again.',
                          );
                        }
                      } finally {
                        if (mounted) setState(() => busy = false);
                      }
                    },
            ),
            if (busy && !reviewingMedia) const LinearProgressIndicator(),
            if (error != null)
              Text(error!, style: const TextStyle(color: Colors.orange)),
            if (summary != null)
              ExpansionTile(
                title: const Text('Latest check details'),
                children: [
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(summary!),
                  ),
                  for (final issue in resolved.take(20))
                    ListTile(
                      leading: const Icon(
                        Icons.check_circle_outline,
                        color: Colors.tealAccent,
                      ),
                      title: Text('${issue['task']}'),
                      subtitle: Text('${issue['evidence']}'),
                    ),
                ],
              ),
            for (final reason in operational)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [Text(reason), action(reason)],
                  ),
                ),
              ),
            for (final issue in issues)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${issue['task']}',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      Text('${issue['reason']}'),
                      const SizedBox(height: 6),
                      Text('${issue['action']}'),
                      action('${issue['task']} ${issue['reason']}'),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 20),
            ExpansionTile(
              title: const Text('Issue history & recovery'),
              subtitle: const Text(
                'Recurring conditions and verified health changes',
              ),
              children: [
                for (final row
                    in (health['issueHistory'] as List? ?? []).whereType<Map>())
                  ListTile(
                    title: Text('${row['reason']}'),
                    subtitle: Text(
                      '${row['episodes']} occurrence(s) · ${row['recoveryAttempts']} recovery request(s)\n'
                      '${(row['resolvedAt'] as num? ?? 0) > 0 ? row['evidence'] : 'Still observed at the latest check'}',
                    ),
                  ),
                if ((health['issueHistory'] as List? ?? []).isEmpty)
                  const ListTile(
                    title: Text('History appears after the next health check.'),
                  ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              'Resolved with evidence (${resolved.length})',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (resolved.isEmpty)
              const Text(
                'Verified repairs will appear here. Closing a review is not proof of delivery.',
              ),
            for (final issue in resolved.take(20))
              ListTile(
                leading: const Icon(
                  Icons.check_circle_outline,
                  color: Colors.tealAccent,
                ),
                title: Text('${issue['task']}'),
                subtitle: Text('${issue['evidence']}'),
              ),
          ],
        ),
      ),
    );
  }
}
