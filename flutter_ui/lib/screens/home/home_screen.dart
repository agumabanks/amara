import 'dart:async';

import 'package:flutter/material.dart';
import '../../main.dart';
import '../../bridge/agent_channel.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  late final AnimationController _pulse;
  Timer? _refreshTimer;
  bool _refreshing = false;
  Map<String, dynamic> _autonomy = const {},
      _mission = const {},
      _market = const {},
      _revenue = const {};
  Map<String, bool> _health = const {};
  bool _loading = true;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _pulse = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 2),
    )..repeat(reverse: true);
    _refresh();
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 5),
      (_) => _refresh(),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _refreshTimer?.cancel();
    _pulse.dispose();
    super.dispose();
  }

  List<Map<String, dynamic>> _maps(dynamic v) => (v as List? ?? const [])
      .whereType<Map>()
      .map((e) => e.cast<String, dynamic>())
      .toList();
  Future<void> _refresh() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final v = await Future.wait<Object>([
        AgentChannel.autonomousDashboard().catchError(
          (_) => <String, dynamic>{},
        ),
        AgentChannel.missionControl().catchError((_) => <String, dynamic>{}),
        AgentChannel.marketDashboard().catchError((_) => <String, dynamic>{}),
        AgentChannel.capabilityHealth().catchError((_) => <String, bool>{}),
        AgentChannel.revenueDashboard()
            .then((e) => e ?? <String, dynamic>{})
            .catchError((_) => <String, dynamic>{}),
      ]);
      if (mounted) {
        setState(() {
          _autonomy = Map<String, dynamic>.from(v[0] as Map);
          _mission = Map<String, dynamic>.from(v[1] as Map);
          _market = Map<String, dynamic>.from(v[2] as Map);
          _health = Map<String, bool>.from(v[3] as Map);
          _revenue = Map<String, dynamic>.from(v[4] as Map);
          _loading = false;
        });
      }
    } finally {
      _refreshing = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final loop =
        (_autonomy['loop'] as Map?)?.cast<String, dynamic>() ?? const {};
    final governor =
        (_autonomy['governor'] as Map?)?.cast<String, dynamic>() ?? const {};
    final learning =
        (_autonomy['learning'] as Map?)?.cast<String, dynamic>() ?? const {};
    final queue =
        (_autonomy['queue'] as Map?)?.cast<String, dynamic>() ?? const {};
    final running = loop['running'] == true;
    final queueCount = _maps(queue['items']).length;
    final schedules = [
      ..._maps(_mission['systemSchedules']),
      ..._maps(_mission['schedules']),
    ].where((e) => e['enabled'] == true).length;
    final templates = _maps(_mission['templates']).length;
    final recent = _maps(learning['recent']);
    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(18, 14, 18, 40),
            children: [
              Row(
                children: [
                  Container(
                    width: 46,
                    height: 46,
                    decoration: BoxDecoration(
                      gradient: const LinearGradient(
                        colors: [Color(0xFF50E3C2), Color(0xFF29A58D)],
                      ),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: const Icon(
                      Icons.auto_awesome,
                      color: Color(0xFF080A0A),
                    ),
                  ),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'AMARA',
                          style: TextStyle(
                            fontSize: 19,
                            fontWeight: FontWeight.w900,
                            letterSpacing: 2,
                          ),
                        ),
                        Text(
                          'Autonomous business operator',
                          style: TextStyle(color: Colors.white54, fontSize: 12),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    onPressed: _refresh,
                    icon: const Icon(Icons.refresh),
                  ),
                ],
              ),
              const SizedBox(height: 18),
              Container(
                padding: const EdgeInsets.all(20),
                decoration: _box(accent: running),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        AnimatedBuilder(
                          animation: _pulse,
                          builder: (_, __) => Container(
                            width: 13,
                            height: 13,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: running
                                  ? const Color(0xFF50E3C2)
                                  : Colors.orange,
                              boxShadow: running
                                  ? [
                                      BoxShadow(
                                        color: const Color(0xFF50E3C2)
                                            .withValues(
                                              alpha: .25 + .35 * _pulse.value,
                                            ),
                                        blurRadius: 12,
                                      ),
                                    ]
                                  : [],
                            ),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            running
                                ? 'Autonomous loop online'
                                : 'Autonomous loop needs attention',
                            style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                        _tag(loop['state']?.toString() ?? 'UNKNOWN'),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      loop['lastSummary']?.toString() ??
                          'Preparing autonomous backend…',
                      style: const TextStyle(color: Colors.white60),
                    ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: _metric(
                            '${loop['cycleCount'] ?? 0}',
                            'cycles',
                          ),
                        ),
                        Expanded(child: _metric('$queueCount', 'queued')),
                        Expanded(child: _metric('$schedules', 'schedules')),
                        Expanded(child: _metric('$templates', 'templates')),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  Expanded(
                    child: _quick(
                      Icons.work_outline,
                      'Work',
                      'Queue & templates',
                      () => getAppShellState(context)?.switchTab(1),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: _quick(
                      Icons.radar,
                      'Market',
                      '${_market['totalListings'] ?? 0} signals',
                      () => getAppShellState(context)?.switchTab(2),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: _quick(
                      Icons.tune,
                      'Controls',
                      'Safety & timing',
                      () => getAppShellState(context)?.switchTab(4),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 22),
              _heading('TODAY AT A GLANCE'),
              _today(governor),
              const SizedBox(height: 22),
              _heading('SYSTEM READINESS'),
              _healthCard(),
              const SizedBox(height: 22),
              _heading('MARKET RADAR'),
              _marketCard(),
              const SizedBox(height: 22),
              _heading('RECENT AUTONOMOUS ACTIVITY'),
              if (recent.isEmpty)
                _empty(
                  'No completed autonomous action yet. The loop is awake and observing policy windows.',
                )
              else
                ...recent.take(6).map(_activity),
              if (_loading)
                const Padding(
                  padding: EdgeInsets.only(top: 16),
                  child: LinearProgressIndicator(),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _today(Map<String, dynamic> g) {
    final today =
        (_revenue['today'] as Map?)?.cast<String, dynamic>() ?? const {};
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: _box(),
      child: Row(
        children: [
          Expanded(child: _metric('${g['attemptsToday'] ?? 0}', 'attempts')),
          Expanded(child: _metric('${g['successesToday'] ?? 0}', 'successes')),
          Expanded(
            child: _metric('${today['qualifiedInquiries'] ?? 0}', 'inquiries'),
          ),
          Expanded(child: _metric('${today['verifiedSales'] ?? 0}', 'sales')),
        ],
      ),
    );
  }

  Widget _healthCard() {
    final checks = {
      'Accessibility': _health['accessibility'] == true,
      'Groq AI': _health['groqConfigured'] == true,
      'Soko': _health['sokoTerminalInstalled'] == true,
      'Soko PIN': _health['sokoPinStored'] == true,
    };
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: _box(),
      child: Wrap(
        spacing: 10,
        runSpacing: 10,
        children: checks.entries
            .map(
              (e) => Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: (e.value ? const Color(0xFF50E3C2) : Colors.orange)
                      .withValues(alpha: .1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      e.value ? Icons.check_circle : Icons.warning_amber,
                      color: e.value ? const Color(0xFF50E3C2) : Colors.orange,
                      size: 16,
                    ),
                    const SizedBox(width: 6),
                    Text(e.key),
                  ],
                ),
              ),
            )
            .toList(),
      ),
    );
  }

  Widget _marketCard() {
    final sources = _maps(_market['sources']);
    final opportunities = _maps(_market['opportunities']).length;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: _box(),
      child: Column(
        children: [
          Row(
            children: sources
                .map(
                  (s) => Expanded(
                    child: _metric(
                      '${s['listings'] ?? 0}',
                      s['source']?.toString() ?? 'source',
                    ),
                  ),
                )
                .toList(),
          ),
          const Divider(height: 24),
          Row(
            children: [
              const Icon(Icons.lightbulb_outline, color: Color(0xFFF97316)),
              const SizedBox(width: 10),
              Expanded(child: Text('$opportunities ranked opportunities')),
              TextButton(
                onPressed: () => getAppShellState(context)?.switchTab(2),
                child: const Text('Open radar'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _activity(Map<String, dynamic> a) => Container(
    margin: const EdgeInsets.only(bottom: 8),
    padding: const EdgeInsets.all(13),
    decoration: _box(),
    child: Row(
      children: [
        Icon(
          a['success'] == true ? Icons.check_circle : Icons.error_outline,
          color: a['success'] == true
              ? const Color(0xFF50E3C2)
              : Colors.redAccent,
          size: 18,
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _pretty(a['action']),
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              Text(
                a['details']?.toString() ?? '',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.white38, fontSize: 11),
              ),
            ],
          ),
        ),
        Text(
          _pretty(a['domain']),
          style: const TextStyle(color: Colors.white38, fontSize: 10),
        ),
      ],
    ),
  );
  Widget _quick(
    IconData icon,
    String title,
    String subtitle,
    VoidCallback tap,
  ) => InkWell(
    onTap: tap,
    borderRadius: BorderRadius.circular(16),
    child: Container(
      padding: const EdgeInsets.all(14),
      decoration: _box(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: const Color(0xFF50E3C2)),
          const SizedBox(height: 9),
          Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
          Text(
            subtitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Colors.white38, fontSize: 10),
          ),
        ],
      ),
    ),
  );
  Widget _heading(String text) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Text(
      text,
      style: const TextStyle(
        color: Colors.white54,
        fontSize: 11,
        letterSpacing: 1.7,
        fontWeight: FontWeight.w800,
      ),
    ),
  );
  Widget _metric(String value, String label) => Column(
    children: [
      Text(
        value,
        style: const TextStyle(
          fontSize: 21,
          fontWeight: FontWeight.w800,
          color: Color(0xFF50E3C2),
        ),
      ),
      Text(label, style: const TextStyle(color: Colors.white38, fontSize: 10)),
    ],
  );
  Widget _tag(String text) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    decoration: BoxDecoration(
      color: const Color(0xFF50E3C2).withValues(alpha: .12),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      text,
      style: const TextStyle(
        color: Color(0xFF50E3C2),
        fontSize: 10,
        fontWeight: FontWeight.w700,
      ),
    ),
  );
  Widget _empty(String text) => Container(
    padding: const EdgeInsets.all(16),
    decoration: _box(),
    child: Text(
      text,
      style: TextStyle(color: Colors.white.withValues(alpha: .45)),
    ),
  );
  BoxDecoration _box({bool accent = false}) => BoxDecoration(
    color: const Color(0xFF121616),
    borderRadius: BorderRadius.circular(17),
    border: accent
        ? Border.all(color: const Color(0xFF50E3C2).withValues(alpha: .28))
        : null,
  );
  String _pretty(dynamic value) => (value?.toString() ?? '')
      .replaceAll('_', ' ')
      .toLowerCase()
      .split(' ')
      .where((e) => e.isNotEmpty)
      .map((e) => '${e[0].toUpperCase()}${e.substring(1)}')
      .join(' ');
}
