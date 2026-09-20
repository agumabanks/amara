import '../../widgets/attention_card.dart';
import '../doctor/doctor_screen.dart';
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
  Map<String, dynamic> _operational = const {};
  Map<String, bool> _health = const {};
  bool _loading = true;
  bool _dataUnavailable = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _pulse = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2600),
    )..repeat(reverse: true);
    _refresh();
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 5),
      (_) => _refresh(),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context)) {
      _pulse.stop();
      _pulse.value = .5;
    } else if (!_pulse.isAnimating) {
      _pulse.repeat(reverse: true);
    }
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
      var unavailable = false;
      final v = await Future.wait<Object>([
        AgentChannel.autonomousDashboard().catchError((_) {
          unavailable = true;
          return _autonomy;
        }),
        AgentChannel.missionControl().catchError((_) => <String, dynamic>{}),
        AgentChannel.marketDashboard().catchError((_) => <String, dynamic>{}),
        AgentChannel.capabilityHealth().catchError((_) => <String, bool>{}),
        AgentChannel.revenueDashboard()
            .then((e) => e ?? <String, dynamic>{})
            .catchError((_) => <String, dynamic>{}),
        AgentChannel.operationalHealth().catchError((_) {
          unavailable = true;
          return _operational;
        }),
      ]);
      if (mounted) {
        setState(() {
          _dataUnavailable = unavailable;
          _autonomy = Map<String, dynamic>.from(v[0] as Map);
          _mission = Map<String, dynamic>.from(v[1] as Map);
          _market = Map<String, dynamic>.from(v[2] as Map);
          _health = Map<String, bool>.from(v[3] as Map);
          _revenue = Map<String, dynamic>.from(v[4] as Map);
          _operational = Map<String, dynamic>.from(v[5] as Map);
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
    final blockers = _maps(loop['blockers']);
    if (_dataUnavailable) {
      blockers.insert(0, {
        'task': 'Live status',
        'reason':
            'Status could not refresh. The last known state may be out of date.',
        'ownerAction': true,
        'action': 'Pull down to retry or open Doctor.',
      });
    }
    for (final reason in (_operational['blockers'] as List? ?? [])) {
      if (!blockers.any(
        (b) =>
            '$reason' == '${b['reason']}' ||
            '$reason' == '${b['task']}: ${b['reason']}',
      )) {
        blockers.add({
          'task': 'Device health',
          'reason': '$reason',
          'ownerAction': true,
          'action': 'Open Doctor for the next step.',
        });
      }
    }
    final queueCount = queue['dueCount'] ?? _maps(queue['items']).length;
    final scheduledCount = queue['scheduledCount'] ?? 0;
    final reviewCount = _maps(queue['needsReview']).length;
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
                          'Your business, in motion',
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
                        _AmaraPresence(
                          running: running && loop['ownerOn'] != false,
                          needsHelp:
                              blockers.isNotEmpty ||
                              _health.values.any((ready) => !ready) ||
                              governor['state'] == 'HALTED' ||
                              governor['state'] == 'DEGRADED',
                          working: loop['state'] == 'EXECUTING',
                          pulse: _pulse,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            loop['ownerOn'] == false
                                ? 'Amara is off'
                                : running
                                ? 'Amara is on'
                                : 'Waiting to reconnect',
                            style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                        _tag(
                          blockers.isNotEmpty
                              ? 'Review'
                              : loop['state'] == 'EXECUTING'
                              ? 'Working'
                              : running
                              ? 'Ready'
                              : 'Offline',
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      loop['lastSummary']?.toString() ??
                          'Preparing autonomous backend…',
                      style: const TextStyle(color: Colors.white60),
                    ),
                    const SizedBox(height: 16),
                    if ((loop['tikTokPostingLimitMinutes'] as num? ?? 0) >
                        0) ...[
                      Text(
                        'TikTok · ${((loop['tikTokPostingRemainingSeconds'] as num? ?? 0) / 60).floor()} min available today',
                        style: const TextStyle(color: Colors.white60),
                      ),
                      const SizedBox(height: 12),
                    ],
                    if (blockers.isEmpty)
                      FilledButton.icon(
                        onPressed: () => Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => const DoctorScreen(),
                          ),
                        ),
                        icon: const Icon(Icons.health_and_safety),
                        label: const Text('Health check'),
                      ),
                    const SizedBox(height: 12),
                    AttentionCard(issues: blockers),
                    Row(
                      children: [
                        Expanded(
                          child: _metric(
                            '${loop['cycleCount'] ?? 0}',
                            'check-ins',
                          ),
                        ),
                        Expanded(child: _metric('$queueCount', 'ready')),
                        Expanded(child: _metric('$schedules', 'schedules')),
                        Expanded(child: _metric('$templates', 'templates')),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Text(
                      '$scheduledCount scheduled for later · $reviewCount need review',
                      style: const TextStyle(color: Colors.white60),
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
              _heading('RECENT ACTIVITY'),
              if (recent.isEmpty)
                _empty('Completed work will appear here.')
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
      'Terminal bridge':
          (_autonomy['sokoBridge'] as Map?)?['state'] == 'connected',
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

/// A tiny expressive companion. Motion communicates presence, never delivery.
class _AmaraPresence extends StatelessWidget {
  const _AmaraPresence({
    required this.running,
    required this.needsHelp,
    required this.working,
    required this.pulse,
  });
  final bool running, needsHelp, working;
  final Animation<double> pulse;

  @override
  Widget build(BuildContext context) => Semantics(
    label: !running
        ? 'Amara resting'
        : needsHelp
        ? 'Amara needs attention'
        : working
        ? 'Amara working'
        : 'Amara is here',
    child: RepaintBoundary(
      child: AnimatedBuilder(
        animation: pulse,
        builder: (_, __) {
          final reduced = MediaQuery.disableAnimationsOf(context);
          final moving = running && !reduced;
          final t = moving ? Curves.easeInOutSine.transform(pulse.value) : .5;
          return SizedBox(
            width: 34,
            height: 34,
            child: Transform.translate(
              offset: Offset(0, moving ? (t - .5) * (working ? 2.5 : 1.5) : 0),
              child: Transform.rotate(
                angle: moving && !needsHelp ? (t - .5) * .08 : 0,
                child: CustomPaint(
                  painter: _AmaraFace(
                    awake: running,
                    needsHelp: needsHelp,
                    blink: moving && pulse.value > .975,
                    glow: t,
                  ),
                ),
              ),
            ),
          );
        },
      ),
    ),
  );
}

class _AmaraFace extends CustomPainter {
  const _AmaraFace({
    required this.awake,
    required this.needsHelp,
    required this.blink,
    required this.glow,
  });
  final bool awake, needsHelp, blink;
  final double glow;

  @override
  void paint(Canvas canvas, Size size) {
    final c = Offset(size.width / 2, size.height / 2);
    final accent = !awake
        ? const Color(0xFF82908E)
        : needsHelp
        ? const Color(0xFFFFBE82)
        : const Color(0xFF50E3C2);
    canvas.drawCircle(
      c,
      16,
      Paint()..color = accent.withValues(alpha: awake ? .09 + glow * .06 : .07),
    );
    canvas.drawCircle(c, 12.5, Paint()..color = const Color(0xFF253B38));
    canvas.drawCircle(
      c,
      12.5,
      Paint()
        ..color = accent.withValues(alpha: .7)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.2,
    );
    final ink = Paint()
      ..color = awake ? const Color(0xFFFFF1E9) : accent
      ..strokeWidth = 1.7
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    for (final x in [-4.3, 4.3]) {
      final eye = c + Offset(x, -1.5);
      if (!awake || blink) {
        canvas.drawLine(
          eye + const Offset(-1.3, 0),
          eye + const Offset(1.3, 0),
          ink,
        );
      } else {
        canvas.drawLine(
          eye + const Offset(0, -1),
          eye + const Offset(0, 1),
          ink,
        );
        canvas.drawLine(eye + Offset(x < 0 ? -1.4 : 1.4, -1.8), eye, ink);
      }
    }
    final mouth = Path()..moveTo(c.dx - 2.5, c.dy + 4);
    mouth.quadraticBezierTo(
      c.dx,
      c.dy + (needsHelp && awake ? 2.8 : 6.7),
      c.dx + 2.5,
      c.dy + 4,
    );
    canvas.drawPath(mouth, ink..strokeWidth = 1.2);
    final blush = Paint()
      ..color = const Color(0xFFFFA8BE).withValues(alpha: awake ? .65 : .2);
    canvas.drawOval(
      Rect.fromCenter(center: c + const Offset(-7, 2.5), width: 3.5, height: 2),
      blush,
    );
    canvas.drawOval(
      Rect.fromCenter(center: c + const Offset(7, 2.5), width: 3.5, height: 2),
      blush,
    );
    // A small rose hair clip gives Amara a consistent feminine detail.
    canvas.drawCircle(
      c + const Offset(8, -9),
      2.3,
      Paint()..color = const Color(0xFFF4A6BF),
    );
  }

  @override
  bool shouldRepaint(_AmaraFace old) =>
      awake != old.awake ||
      needsHelp != old.needsHelp ||
      blink != old.blink ||
      glow != old.glow;
}
