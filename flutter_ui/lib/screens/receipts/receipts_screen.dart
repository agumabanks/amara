import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Owner-visible receipt for every post and send attempt.
///
/// Receipts project the canonical side-effect ledger; the outcome shown here matches
/// stored evidence. Customer conversation content is never included.
class ReceiptsScreen extends StatefulWidget {
  const ReceiptsScreen({super.key});
  @override
  State<ReceiptsScreen> createState() => _ReceiptsScreenState();
}

class _ReceiptsScreenState extends State<ReceiptsScreen> {
  static const channel = MethodChannel('com.sanaa.agent/core');
  static const modules = [
    'All modules',
    'TikTok',
    'YouTube',
    'WhatsApp',
    'Soko',
    'Market',
    'Doctor',
    'Memory',
    'General',
  ];
  static const outcomes = [
    'All outcomes',
    'verified',
    'dispatched_unverified',
    'uncertain',
    'failed_before_dispatch',
    'held',
    'prepared',
    'completed',
    'cancelled',
    'expired',
  ];
  static const outcomeLabels = {
    'verified': 'Verified',
    'dispatched_unverified': 'Dispatched · unverified',
    'uncertain': 'Uncertain',
    'failed_before_dispatch': 'Failed before dispatch',
    'held': 'Held',
    'prepared': 'Prepared',
    'completed': 'Completed (no external effect)',
    'cancelled': 'Cancelled',
    'expired': 'Expired',
  };
  static const outcomeIcons = {
    'verified': Icons.verified_outlined,
    'dispatched_unverified': Icons.schedule_outlined,
    'uncertain': Icons.help_outline,
    'failed_before_dispatch': Icons.cancel_outlined,
    'held': Icons.pause_circle_outline,
    'prepared': Icons.fact_check_outlined,
    'completed': Icons.check_circle_outline,
    'cancelled': Icons.block_outlined,
    'expired': Icons.timer_off_outlined,
  };
  static const outcomeColors = {
    'verified': Color(0xFF50E3C2),
    'dispatched_unverified': Color(0xFFF5C542),
    'uncertain': Color(0xFFF97316),
    'failed_before_dispatch': Color(0xFFF26D6D),
    'held': Color(0xFF9AA5B1),
    'prepared': Color(0xFF9AA5B1),
    'completed': Color(0xFF50E3C2),
    'cancelled': Color(0xFF9AA5B1),
    'expired': Color(0xFF9AA5B1),
  };

  List<Map<dynamic, dynamic>> receipts = [];
  String module = 'All modules';
  String outcome = 'All outcomes';
  String query = '';
  bool busy = false;
  String? error;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final value = await channel.invokeListMethod<dynamic>('actionReceipts', {
        if (module != 'All modules') 'module': module,
        if (outcome != 'All outcomes') 'outcome': outcome,
        if (query.isNotEmpty) 'query': query,
        'limit': 100,
      });
      if (mounted) {
        setState(
          () => receipts = (value ?? const []).whereType<Map>().toList(),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(
          () => error =
              'Receipts could not load. ${e is PlatformException ? e.message : e}',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<List<Map<dynamic, dynamic>>> history(String key) async {
    try {
      final value = await channel.invokeListMethod<dynamic>('receiptHistory', {
        'key': key,
      });
      return (value ?? const []).whereType<Map>().toList();
    } catch (_) {
      return const [];
    }
  }

  String when(num? millis) {
    if (millis == null || millis == 0) return 'not observed';
    final at = DateTime.fromMillisecondsSinceEpoch(millis.toInt());
    return '${at.year}-${at.month.toString().padLeft(2, '0')}-${at.day.toString().padLeft(2, '0')} '
        '${at.hour.toString().padLeft(2, '0')}:${at.minute.toString().padLeft(2, '0')}';
  }

  String explain(Map<dynamic, dynamic> receipt) {
    final outcome = receipt['outcome'] as String? ?? '';
    final stage = receipt['stage'] as String? ?? '';
    final certainty = receipt['dispatch_certainty'] as String? ?? '';
    final target = receipt['target'] as String? ?? '';
    final platform = receipt['platform'] as String? ?? '';
    final evidence = receipt['evidence'] as String? ?? '';
    final ref = receipt['platform_ref'] as String? ?? '';
    final parts = <String>[];
    switch (outcome) {
      case 'verified':
        parts.add('This effect was dispatched and independently verified.');
      case 'dispatched_unverified':
        parts.add(
          'The trigger was sent, but verification has not confirmed the result yet.',
        );
      case 'uncertain':
        parts.add(
          'Whether this effect took effect could not be proven. It was not retried automatically; review before any replay.',
        );
      case 'failed_before_dispatch':
        parts.add(
          'This attempt failed before anything was dispatched, so no post or message was published.',
        );
      case 'held':
        parts.add(
          'This attempt is held by a policy or precondition; it was not dispatched.',
        );
      case 'prepared':
        parts.add('This action was prepared but nothing was dispatched yet.');
      case 'completed':
        parts.add('This task completed without an external effect.');
      case 'cancelled':
        parts.add('This attempt was cancelled before dispatch.');
      case 'expired':
        parts.add('This attempt expired before dispatch.');
    }
    if (platform.isNotEmpty) parts.add('Platform: $platform.');
    if (target.isNotEmpty) parts.add('Destination: $target.');
    if (certainty.isNotEmpty && certainty != 'verified') {
      parts.add('Dispatch certainty: $certainty.');
    }
    if (stage.isNotEmpty) {
      parts.add('Last stage: ${stage.replaceAll('_', ' ')}.');
    }
    if (evidence.isNotEmpty) {
      parts.add('Evidence: $evidence');
    }
    if (ref.isEmpty) {
      parts.add('Platform ID/URL: not observed.');
    } else {
      parts.add('Platform reference: $ref');
    }
    return parts.join(' ');
  }

  String followUp(Map<dynamic, dynamic> receipt) {
    final outcome = receipt['outcome'] as String? ?? '';
    switch (outcome) {
      case 'uncertain':
      case 'dispatched_unverified':
        return 'Follow-up needed: confirm the result before relying on it. Do not replay this attempt automatically.';
      case 'failed_before_dispatch':
        return 'Follow-up needed: review the failure and the affected app before another attempt.';
      case 'held':
        return 'Follow-up needed: clear the stated precondition or policy hold.';
      default:
        return 'No follow-up needed.';
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xFF0D1111),
    appBar: AppBar(
      backgroundColor: const Color(0xFF0D1111),
      foregroundColor: Colors.white,
      title: const Text('Receipts'),
      actions: [
        IconButton(
          onPressed: busy ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
          child: Row(
            children: [
              Expanded(
                child: DropdownButtonFormField<String>(
                  initialValue: module,
                  dropdownColor: const Color(0xFF161B1B),
                  decoration: const InputDecoration(
                    labelText: 'Module',
                    border: OutlineInputBorder(),
                  ),
                  items: modules
                      .map((m) => DropdownMenuItem(value: m, child: Text(m)))
                      .toList(),
                  onChanged: (value) {
                    if (value != null) {
                      module = value;
                      load();
                    }
                  },
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: DropdownButtonFormField<String>(
                  initialValue: outcome,
                  dropdownColor: const Color(0xFF161B1B),
                  decoration: const InputDecoration(
                    labelText: 'Outcome',
                    border: OutlineInputBorder(),
                  ),
                  items: outcomes
                      .map(
                        (o) => DropdownMenuItem(
                          value: o,
                          child: Text(outcomeLabels[o] ?? o),
                        ),
                      )
                      .toList(),
                  onChanged: (value) {
                    if (value != null) {
                      outcome = value;
                      load();
                    }
                  },
                ),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: TextField(
            decoration: const InputDecoration(
              hintText: 'Search capability, destination or stage',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.search),
            ),
            onSubmitted: (value) {
              query = value.trim();
              load();
            },
          ),
        ),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 12),
          child: Align(
            alignment: Alignment.centerLeft,
            child: Text(
              'Every attempted effect has an outcome record, including preparation failures before dispatch. A failed attempt is never shown as a published post.',
              style: TextStyle(fontSize: 11, color: Color(0xFF9AA5B1)),
            ),
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: load,
            child: receipts.isEmpty && !busy
                ? ListView(
                    children: [
                      const Padding(
                        padding: EdgeInsets.all(24),
                        child: Center(
                          child: Text(
                            'No receipts recorded yet for this filter.',
                          ),
                        ),
                      ),
                    ],
                  )
                : ListView.builder(
                    itemCount: receipts.length,
                    itemBuilder: (context, index) =>
                        _receiptTile(receipts[index]),
                  ),
          ),
        ),
      ],
    ),
  );

  Widget _receiptTile(Map<dynamic, dynamic> receipt) {
    final outcome = receipt['outcome'] as String? ?? '';
    final color = outcomeColors[outcome] ?? const Color(0xFF9AA5B1);
    final icon = outcomeIcons[outcome] ?? Icons.receipt_long;
    final key = receipt['idempotency_key'] as String? ?? '';
    return Card(
      color: const Color(0xFF161B1B),
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: ExpansionTile(
        iconColor: color,
        collapsedIconColor: color,
        leading: Icon(icon, color: color),
        title: Text(
          outcomeLabels[outcome] ?? outcome,
          style: TextStyle(color: color, fontWeight: FontWeight.w600),
        ),
        subtitle: Text(
          '${receipt['capability'] ?? ''} · ${when(receipt['updated_at'] as num?)}'
          '${receipt['attempt'] is int && (receipt['attempt'] as int) > 0 ? ' · attempt ${(receipt['attempt'] as int) + 1}' : ''}',
          style: const TextStyle(fontSize: 11, color: Color(0xFF9AA5B1)),
        ),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  explain(receipt),
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
                const SizedBox(height: 8),
                Text(
                  followUp(receipt),
                  style: const TextStyle(
                    color: Color(0xFFF5C542),
                    fontSize: 12,
                  ),
                ),
                const SizedBox(height: 8),
                if (receipt['shop_scope'] is String &&
                    (receipt['shop_scope'] as String).isNotEmpty)
                  Text(
                    'Shop/account: ${receipt['shop_scope']}',
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFF9AA5B1),
                    ),
                  ),
                if (receipt['intended_at'] is int &&
                    (receipt['intended_at'] as int) > 0)
                  Text(
                    'Intended schedule: ${when(receipt['intended_at'] as num?)}',
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFF9AA5B1),
                    ),
                  ),
                Text(
                  'Prepared: ${when(receipt['prepared_at'] as num?)} · Dispatched: ${when(receipt['dispatched_at'] as num?)} · Settled: ${when(receipt['settled_at'] as num?)}',
                  style: const TextStyle(
                    fontSize: 11,
                    color: Color(0xFF9AA5B1),
                  ),
                ),
                if (receipt['content_hash'] is String &&
                    (receipt['content_hash'] as String).isNotEmpty)
                  Text(
                    'Content hash: ${receipt['content_hash']}',
                    style: const TextStyle(
                      fontSize: 10,
                      color: Color(0xFF9AA5B1),
                    ),
                  ),
                if (receipt['media_digest'] is String &&
                    (receipt['media_digest'] as String).isNotEmpty)
                  Text(
                    'Media digest: ${receipt['media_digest']}',
                    style: const TextStyle(
                      fontSize: 10,
                      color: Color(0xFF9AA5B1),
                    ),
                  ),
                Text(
                  'App version: ${receipt['app_version'] ?? ''}',
                  style: const TextStyle(
                    fontSize: 11,
                    color: Color(0xFF9AA5B1),
                  ),
                ),
                const SizedBox(height: 8),
                FutureBuilder<List<Map<dynamic, dynamic>>>(
                  future: history(key),
                  builder: (_, snapshot) {
                    final events = snapshot.data ?? const [];
                    if (events.isEmpty) return const SizedBox.shrink();
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'History (oldest first, original evidence retained):',
                          style: TextStyle(
                            fontSize: 11,
                            color: Color(0xFF9AA5B1),
                          ),
                        ),
                        for (final event in events)
                          Padding(
                            padding: const EdgeInsets.only(top: 4),
                            child: Text(
                              '· ${when(event['at'] as num?)} — ${outcomeLabels[event['outcome']] ?? event['outcome']}'
                              '${event['evidence'] is String && (event['evidence'] as String).isNotEmpty ? ': ${event['evidence']}' : ''}',
                              style: const TextStyle(
                                fontSize: 11,
                                color: Color(0xFF9AA5B1),
                              ),
                            ),
                          ),
                      ],
                    );
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
