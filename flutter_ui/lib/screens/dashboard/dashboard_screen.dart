import 'dart:async';
import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';
import '../chat/chat_screen.dart';
import '../commercial/commercial_screen.dart';
import '../permissions/phone_access_screen.dart';
import '../work/mission_control_screen.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});
  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pulse;
  Map<String, dynamic> _status = const {};
  Map<String, dynamic> _revenue = const {};
  Map<String, bool> _health = const {};
  String? _error;

  @override
  void initState() {
    super.initState();
    _pulse = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 2),
    )..repeat(reverse: true);
    _refresh();
  }

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final revenue = await AgentChannel.revenueDashboard().catchError(
        (_) => <String, dynamic>{},
      );
      final results = await Future.wait<dynamic>([
        AgentChannel.agentStatus().catchError((_) => <String, dynamic>{}),
        AgentChannel.capabilityHealth(),
      ]);
      if (mounted) {
        setState(() {
          _status = results[0] as Map<String, dynamic>;
          _health = results[1] as Map<String, bool>;
          _revenue = (revenue ?? const {}).cast<String, dynamic>();
          _error = null;
        });
      }
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    final today =
        (_status['today'] as Map?)?.cast<String, dynamic>() ?? const {};
    final activity = (_status['activity'] as List?)?.cast<Map>() ?? const [];
    final waiting = (today['waiting_for_owner'] as num?)?.toInt() ?? 0;
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text(
          'Amara',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
        actions: [
          IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
          children: [
            _StatusCard(pulse: _pulse, active: _status['active'] == true),
            const SizedBox(height: 16),
            _PhoneAccessCard(
              health: _health,
              onTap: () => Navigator.of(context)
                  .push(
                    MaterialPageRoute(
                      builder: (_) => const PhoneAccessScreen(),
                    ),
                  )
                  .then((_) => _refresh()),
            ),
            const SizedBox(height: 16),
            _MissionControlCard(
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const MissionControlScreen()),
              ),
            ),
            const SizedBox(height: 16),
            _CommandCard(
              onTap: () => Navigator.of(
                context,
              ).push(MaterialPageRoute(builder: (_) => const ChatScreen())),
            ),
            const SizedBox(height: 16),
            if (_revenue.isNotEmpty) ...[
              _RevenueCard(
                revenue: _revenue,
                onTap: () => Navigator.of(context)
                    .push(
                      MaterialPageRoute(
                        settings: const RouteSettings(name: '/commercial'),
                        builder: (_) => CommercialScreen(revenue: _revenue),
                      ),
                    )
                    .then((_) => _refresh()),
              ),
              const SizedBox(height: 16),
            ],
            _WorkCard(today: today),
            if (waiting > 0) ...[
              const SizedBox(height: 16),
              _EscalationCard(
                waiting: waiting,
                onSendSuggestedReply: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => const ChatScreen(
                      initialMessage:
                          'Send the suggested reply to the latest customer needing attention.',
                    ),
                  ),
                ),
                onHandleMyself: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => const ChatScreen(
                      initialMessage:
                          'Show me the latest customer requests that need my judgment.',
                    ),
                  ),
                ),
              ),
            ],
            const SizedBox(height: 26),
            const Text(
              'ACTIVITY',
              style: TextStyle(
                color: Colors.white38,
                fontSize: 11,
                letterSpacing: 2,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 10),
            if (_error != null)
              _EmptyActivity(
                text:
                    'I couldn’t reach the activity log. Pull down to try again.',
              )
            else if (activity.isEmpty)
              const _EmptyActivity(
                text: 'Everything is quiet. I’ll report here when work begins.',
              )
            else
              ...activity.map(
                (item) => _ActivityTile(item: item.cast<String, dynamic>()),
              ),
          ],
        ),
      ),
    );
  }
}

class _MissionControlCard extends StatelessWidget {
  const _MissionControlCard({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: const Color(0xFF121616),
    borderRadius: BorderRadius.circular(20),
    child: InkWell(
      borderRadius: BorderRadius.circular(20),
      onTap: onTap,
      child: const Padding(
        padding: EdgeInsets.all(18),
        child: Row(
          children: [
            Icon(Icons.assignment_outlined, color: Color(0xFF50E3C2)),
            SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Work control',
                    style: TextStyle(fontWeight: FontWeight.w800),
                  ),
                  SizedBox(height: 3),
                  Text(
                    'Shop findings, approvals and recurring work',
                    style: TextStyle(color: Colors.white54, fontSize: 13),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: Colors.white38),
          ],
        ),
      ),
    ),
  );
}

class _PhoneAccessCard extends StatelessWidget {
  const _PhoneAccessCard({required this.health, required this.onTap});
  final Map<String, bool> health;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final critical = [
      health['accessibility'] == true,
      health['accessibilityBound'] == true,
      health['groqConfigured'] == true,
      health['sokoTerminalInstalled'] == true,
      health['sokoBuyerInstalled'] == true,
      health['sokoPinStored'] == true,
    ];
    final ready = critical.where((value) => value).length;
    final allReady = health.isNotEmpty && ready == critical.length;
    return Material(
      color: allReady
          ? const Color(0xFF50E3C2).withValues(alpha: .08)
          : const Color(0xFFF97316).withValues(alpha: .11),
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Row(
            children: [
              Icon(
                allReady
                    ? Icons.health_and_safety_outlined
                    : Icons.build_circle_outlined,
                color: allReady
                    ? const Color(0xFF50E3C2)
                    : const Color(0xFFF97316),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      allReady
                          ? 'Phone access is healthy'
                          : 'Phone access needs attention',
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '$ready of ${critical.length} core capabilities ready · Tap for exact fixes',
                      style: const TextStyle(
                        color: Colors.white54,
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: Colors.white38),
            ],
          ),
        ),
      ),
    );
  }
}

class _CommandCard extends StatelessWidget {
  const _CommandCard({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: const Color(0xFF50E3C2),
    borderRadius: BorderRadius.circular(20),
    child: InkWell(
      borderRadius: BorderRadius.circular(20),
      onTap: onTap,
      child: const Padding(
        padding: EdgeInsets.all(20),
        child: Row(
          children: [
            Icon(Icons.auto_awesome, color: Color(0xFF080A0A)),
            SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Talk to Amara',
                    style: TextStyle(
                      color: Color(0xFF080A0A),
                      fontWeight: FontWeight.w900,
                      fontSize: 17,
                    ),
                  ),
                  SizedBox(height: 3),
                  Text(
                    'Message her like you would a trusted employee.',
                    style: TextStyle(color: Color(0xB3080A0A)),
                  ),
                ],
              ),
            ),
            Icon(Icons.arrow_forward, color: Color(0xFF080A0A)),
          ],
        ),
      ),
    ),
  );
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.pulse, required this.active});
  final Animation<double> pulse;
  final bool active;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(22),
    decoration: BoxDecoration(
      color: const Color(0xFF121616),
      borderRadius: BorderRadius.circular(22),
      border: Border.all(color: const Color(0xFF50E3C2).withValues(alpha: .18)),
    ),
    child: Row(
      children: [
        AnimatedBuilder(
          animation: pulse,
          builder: (_, __) => Container(
            width: 14,
            height: 14,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: active ? const Color(0xFF50E3C2) : Colors.white30,
              boxShadow: active
                  ? [
                      BoxShadow(
                        color: const Color(
                          0xFF50E3C2,
                        ).withValues(alpha: .15 + pulse.value * .45),
                        blurRadius: 8 + pulse.value * 10,
                        spreadRadius: pulse.value * 3,
                      ),
                    ]
                  : [],
            ),
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                active ? 'Amara is active' : 'Amara is reconnecting',
                style: const TextStyle(
                  fontSize: 19,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                active
                    ? 'Watching the business and ready to act'
                    : 'Pull down to refresh status',
                style: const TextStyle(color: Colors.white54),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _RevenueCard extends StatelessWidget {
  const _RevenueCard({required this.revenue, this.onTap});
  final Map<String, dynamic> revenue;
  final VoidCallback? onTap;

  String _n(String key) => (revenue[key] ?? 0).toString();
  String _money(String key) =>
      'UGX ${(revenue[key] ?? 0 as num).toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (m) => ',')}';

  @override
  Widget build(BuildContext context) {
    final policyConfigured = revenue['policyConfigured'] == true;
    final profitState = revenue['profitState']?.toString() ?? 'UNKNOWN';
    final profitKnown = profitState == 'KNOWN';
    final funnel = (revenue['funnelByStage'] ?? {}).cast<String, dynamic>();
    return Material(
      color: const Color(0xFF121616),
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(
                    Icons.trending_up,
                    color: Color(0xFF4ADE80),
                    size: 18,
                  ),
                  const SizedBox(width: 8),
                  const Text(
                    'REVENUE',
                    style: TextStyle(
                      color: Colors.white70,
                      fontSize: 11,
                      letterSpacing: 2,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const Spacer(),
                  if (!policyConfigured)
                    const Text(
                      'POLICY NOT CONFIGURED',
                      style: TextStyle(color: Colors.amberAccent, fontSize: 10),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                'Today: ${_n('qualifiedInquiriesToday')} qualified inquiries '
                '(target ${revenue['dailyInquiryTarget'] ?? '—'})\n'
                'This week: ${_n('weeklyVerifiedSales')} verified sales '
                '(target ${revenue['weeklySaleTarget'] ?? '—'})',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 13,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                'Direct ${_money('attributedDirectRevenue')} · Influenced ${_money('attributedInfluencedRevenue')}\n'
                'Unattributed ${_money('unattributedRevenue')}',
                style: const TextStyle(
                  color: Colors.white60,
                  fontSize: 12,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                profitKnown
                    ? 'Gross profit (30d): ${revenue['monthlyGrossProfit']} UGX — every figure links to ledger evidence.'
                    : 'Profit: UNKNOWN ($profitState). Revenue is never shown as profit.',
                style: TextStyle(
                  color: profitKnown ? Colors.white54 : Colors.orangeAccent,
                  fontSize: 11.5,
                  height: 1.4,
                ),
              ),
              if (funnel.isNotEmpty) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: funnel.entries
                      .where((e) => (e.value as num? ?? 0) > 0)
                      .map(
                        (e) => Chip(
                          label: Text(
                            '${e.key} ${e.value}',
                            style: const TextStyle(fontSize: 10),
                          ),
                          backgroundColor: const Color(0xFF1C2420),
                        ),
                      )
                      .toList(),
                ),
              ],
              if ((revenue['actionsAwaitingApproval'] as num? ?? 0) > 0 ||
                  (revenue['uncertainOutcomes'] as num? ?? 0) > 0) ...[
                const SizedBox(height: 8),
                Text(
                  '${revenue['actionsAwaitingApproval']} action(s) awaiting your approval · '
                  '${revenue['uncertainOutcomes']} unproven outcome(s)',
                  style: const TextStyle(
                    color: Colors.amberAccent,
                    fontSize: 11,
                  ),
                ),
              ],
              const SizedBox(height: 10),
              Row(
                children: [
                  const Icon(Icons.touch_app, color: Colors.white24, size: 12),
                  const SizedBox(width: 4),
                  Text(
                    'Tap any figure to open its ledger evidence',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.3),
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _WorkCard extends StatelessWidget {
  const _WorkCard({required this.today});
  final Map<String, dynamic> today;
  int value(String key) => (today[key] as num?)?.toInt() ?? 0;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(22),
    decoration: BoxDecoration(
      color: const Color(0xFF121616),
      borderRadius: BorderRadius.circular(22),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          "TODAY'S WORK",
          style: TextStyle(
            color: Colors.white38,
            fontSize: 11,
            letterSpacing: 2,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 18),
        _row(
          'Replied to messages',
          value('messages_replied'),
          Icons.chat_bubble_outline,
        ),
        _row(
          'Listings improved',
          value('listings_improved'),
          Icons.storefront_outlined,
        ),
        _row(
          'Morning broadcast',
          value('morning_broadcasts'),
          Icons.wb_sunny_outlined,
        ),
        _row('Sales made', value('sales'), Icons.track_changes, target: true),
        const Divider(height: 26, color: Colors.white10),
        Row(
          children: [
            const Expanded(
              child: Text(
                'Revenue generated',
                style: TextStyle(color: Colors.white60),
              ),
            ),
            Text(
              '${_money(value('revenue_ugx'))} UGX',
              style: const TextStyle(
                color: Color(0xFF50E3C2),
                fontWeight: FontWeight.w800,
              ),
            ),
          ],
        ),
        const SizedBox(height: 13),
        Row(
          children: [
            const Expanded(
              child: Text(
                'Waiting for you',
                style: TextStyle(color: Colors.white60),
              ),
            ),
            Text(
              '${value('waiting_for_owner')}  ${value('waiting_for_owner') > 0 ? '⚠️' : '✅'}',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
          ],
        ),
      ],
    ),
  );
  Widget _row(String label, int count, IconData icon, {bool target = false}) =>
      Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: Row(
          children: [
            Icon(
              icon,
              size: 19,
              color: target ? const Color(0xFFF97316) : const Color(0xFF50E3C2),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(label, style: const TextStyle(color: Colors.white70)),
            ),
            Text(
              '$count  ${target ? '🎯' : '✅'}',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
          ],
        ),
      );
  String _money(int value) => value.toString().replaceAllMapped(
    RegExp(r'(?<=\d)(?=(\d{3})+(?!\d))'),
    (_) => ',',
  );
}

class _EscalationCard extends StatelessWidget {
  const _EscalationCard({
    required this.waiting,
    required this.onSendSuggestedReply,
    required this.onHandleMyself,
  });
  final int waiting;
  final VoidCallback onSendSuggestedReply;
  final VoidCallback onHandleMyself;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(22),
    decoration: BoxDecoration(
      color: const Color(0xFFF97316).withValues(alpha: .12),
      borderRadius: BorderRadius.circular(22),
      border: Border.all(color: const Color(0xFFF97316).withValues(alpha: .55)),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          '⚠️  ACTION NEEDED',
          style: TextStyle(
            color: Color(0xFFF97316),
            fontWeight: FontWeight.w900,
            letterSpacing: 1,
          ),
        ),
        const SizedBox(height: 12),
        Text(
          '$waiting customer request${waiting == 1 ? '' : 's'} need your judgment. Open the latest activity for the full context.',
          style: const TextStyle(height: 1.45, fontSize: 15),
        ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton(
              onPressed: onSendSuggestedReply,
              child: const Text('Send suggested reply'),
            ),
            OutlinedButton(
              onPressed: onHandleMyself,
              child: const Text('Handle myself'),
            ),
          ],
        ),
      ],
    ),
  );
}

class _ActivityTile extends StatelessWidget {
  const _ActivityTile({required this.item});
  final Map<String, dynamic> item;
  @override
  Widget build(BuildContext context) => ExpansionTile(
    tilePadding: EdgeInsets.zero,
    childrenPadding: const EdgeInsets.fromLTRB(48, 0, 8, 14),
    leading: CircleAvatar(
      backgroundColor: Colors.white10,
      child: Icon(
        _icon(item['module']?.toString()),
        color: item['success'] == true
            ? const Color(0xFF50E3C2)
            : const Color(0xFFF97316),
        size: 19,
      ),
    ),
    title: Text(
      item['summary']?.toString() ?? 'Agent activity',
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
    ),
    subtitle: Text(
      _time(item['created_at']?.toString()),
      style: const TextStyle(color: Colors.white38, fontSize: 12),
    ),
    children: [
      Align(
        alignment: Alignment.centerLeft,
        child: Text(
          '${item['summary'] ?? ''}\nModule: ${item['module'] ?? 'agent'} • Action: ${item['action'] ?? 'work'}',
          style: const TextStyle(color: Colors.white60, height: 1.5),
        ),
      ),
    ],
  );
  IconData _icon(String? module) => switch (module) {
    'conversation' => Icons.chat_bubble_outline,
    'listing_intelligence' => Icons.storefront_outlined,
    'morning_broadcast' => Icons.wb_sunny_outlined,
    'follow_up' => Icons.schedule,
    _ => Icons.auto_awesome,
  };
  String _time(String? date) {
    final parsed = DateTime.tryParse(date ?? '')?.toLocal();
    if (parsed == null) return '';
    return '${parsed.hour.toString().padLeft(2, '0')}:${parsed.minute.toString().padLeft(2, '0')}';
  }
}

class _EmptyActivity extends StatelessWidget {
  const _EmptyActivity({required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 30),
    child: Center(
      child: Text(
        text,
        textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.white38, height: 1.5),
      ),
    ),
  );
}
