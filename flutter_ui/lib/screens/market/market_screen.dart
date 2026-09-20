import 'insight_sheet.dart';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../doctor/doctor_screen.dart';
import '../../bridge/agent_channel.dart';

class MarketScreen extends StatefulWidget {
  const MarketScreen({super.key});
  @override
  State<MarketScreen> createState() => _MarketScreenState();
}

class _MarketScreenState extends State<MarketScreen>
    with WidgetsBindingObserver {
  Map<String, dynamic> _data = const {};
  bool _loading = true;
  String? _error;
  String? _researchResult;
  bool _requesting = false;
  String _query = "";
  bool _refreshing = false;
  Timer? _refreshTimer;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 30),
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
    super.dispose();
  }

  List<Map<String, dynamic>> _maps(dynamic value) =>
      (value as List? ?? const [])
          .whereType<Map>()
          .map((e) => e.cast<String, dynamic>())
          .toList();
  Future<void> _refresh() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final value = await AgentChannel.marketDashboard();
      if (mounted) {
        setState(() {
          _data = value;
          _error = null;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = "Market data could not refresh. Pull down to retry.";
        });
      }
    } finally {
      _refreshing = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final growth = (_data['growth'] as Map? ?? const {})
        .cast<String, dynamic>();
    final outcomes = (growth['promotionOutcomes'] as Map? ?? const {});
    final sources = _maps(_data['sources']);
    final opportunities = _maps(_data['opportunities']);
    final comparisons = _maps(_data['sokoComparisons']);
    final listings = _maps(_data['recentListings']);
    final total = (_data['totalListings'] as num?)?.toInt() ?? 0;
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Market Intelligence',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
        actions: [
          IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(18, 4, 18, 40),
                children: [
                  if (_error != null) _empty(_error!),
                  if ((_data['catalogueBlocker']?.toString() ?? '').isNotEmpty)
                    _empty(
                      'Shop comparison unavailable: ${_data['catalogueBlocker']}',
                    ),
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: _box(accent: true),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'MARKET RADAR',
                          style: TextStyle(
                            color: Color(0xFF50E3C2),
                            fontWeight: FontWeight.w800,
                            letterSpacing: 1.6,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          '$total saved observations',
                          style: const TextStyle(
                            fontSize: 27,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        Text(
                          _data['researchScope']?.toString() ??
                              'Jiji and Jumia market evidence',
                          style: TextStyle(color: Colors.white54),
                        ),
                        const SizedBox(height: 14),
                        TextField(
                          maxLength: 120,
                          onChanged: (value) => _query = value,
                          decoration: const InputDecoration(
                            labelText: 'Research a product on Jiji',
                            hintText: 'e.g. receipt printer',
                            helperText:
                                'Leave blank for Printers & Scanners. Jumia checks featured offers.',
                          ),
                        ),
                        FilledButton.icon(
                          onPressed: _requesting
                              ? null
                              : () async {
                                  setState(() => _requesting = true);
                                  try {
                                    final result =
                                        await const MethodChannel(
                                          'com.sanaa.agent/core',
                                        ).invokeMapMethod<String, dynamic>(
                                          'requestMarketResearch',
                                          {'query': _query.trim()},
                                        ) ??
                                        {};
                                    if (!mounted) return;
                                    setState(
                                      () => _researchResult = [
                                        if ((result['queued'] as List? ?? [])
                                            .isNotEmpty)
                                          'Queued: ${(result['queued'] as List).join(', ')}. Runs when phone time is available.',
                                        ...(result['blockers'] as List? ?? [])
                                            .map((e) => e.toString()),
                                      ].join('\n'),
                                    );
                                  } catch (_) {
                                    if (mounted) {
                                      setState(
                                        () => _researchResult =
                                            'Research could not be queued. Try again.',
                                      );
                                    }
                                  } finally {
                                    if (mounted) {
                                      setState(() => _requesting = false);
                                    }
                                  }
                                },
                          icon: const Icon(Icons.radar),
                          label: Text(
                            _requesting ? 'Queueing…' : 'Research now',
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (_researchResult != null) _empty(_researchResult!),
                  TextButton.icon(
                    onPressed: () => Navigator.push(
                      context,
                      MaterialPageRoute(builder: (_) => const DoctorScreen()),
                    ),
                    icon: const Icon(Icons.health_and_safety),
                    label: const Text('Research blocked? Open Doctor'),
                  ),
                  const SizedBox(height: 18),
                  ExpansionTile(
                    title: const Text('Growth & campaign results'),
                    children: [
                      _empty(
                        '${growth['review'] ?? 'Waiting for catalogue review'}\n\n'
                        'Group promotions this week: ${outcomes['VERIFIED'] ?? 0} verified, '
                        '${outcomes['UNCERTAIN'] ?? 0} awaiting verification.\n'
                        '${growth['revenueStatus'] ?? 'Revenue needs confirmed orders and payments.'}',
                      ),
                      ..._maps(growth['listingPlans'])
                          .take(5)
                          .map((p) => _empty('${p['title']}\n${p['action']}')),
                      ..._maps(growth['sourcingBriefs'])
                          .take(3)
                          .map(
                            (p) => _empty(
                              '${p['category']}\n${p['evidence']}\n${p['action']}',
                            ),
                          ),
                      ..._maps(growth['replyOutcomes']).map(
                        (r) => _empty(
                          'Replies: ${r['count']} ${r['outcome']} · average '
                          '${((r['averageLatencySeconds'] as num?) ?? 0).round()} seconds from notification',
                        ),
                      ),
                      const SizedBox(height: 22),
                    ],
                  ),
                  _heading('DATA SOURCES'),
                  Row(
                    children: sources
                        .map(
                          (s) => Expanded(
                            child: Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: _source(s),
                            ),
                          ),
                        )
                        .toList(),
                  ),
                  const SizedBox(height: 22),
                  _heading('OPPORTUNITIES'),
                  if (opportunities.isEmpty)
                    _empty(
                      'Amara needs market observations before she can rank opportunities.',
                    )
                  else
                    ...opportunities.map(_opportunity),
                  const SizedBox(height: 22),
                  _heading('SOKO VS MARKET'),
                  if (comparisons.isEmpty)
                    _empty(
                      'Sync the Soko catalogue to unlock price positioning.',
                    )
                  else
                    ...comparisons.map(_comparison),
                  const SizedBox(height: 22),
                  _heading('RECENT EVIDENCE'),
                  if (listings.isEmpty)
                    _empty('No market evidence collected yet.')
                  else
                    ...listings.map(_listing),
                ],
              ),
            ),
    );
  }

  Widget _source(Map<String, dynamic> s) {
    final count = (s['listings'] as num?)?.toInt() ?? 0;
    return Container(
      padding: const EdgeInsets.all(15),
      decoration: _box(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                count > 0 ? Icons.check_circle : Icons.radio_button_unchecked,
                color: count > 0 ? const Color(0xFF50E3C2) : Colors.white30,
                size: 17,
              ),
              const SizedBox(width: 6),
              Text(
                s['source']?.toString() ?? '',
                style: const TextStyle(fontWeight: FontWeight.w800),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            '$count',
            style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
          ),
          Text(
            (s['lastObservedAt'] as num? ?? 0) > 0
                ? 'Updated ${DateTime.fromMillisecondsSinceEpoch((s['lastObservedAt'] as num).toInt()).toLocal().toString().substring(0, 16)}'
                : 'No evidence yet',
            style: TextStyle(color: Colors.white38, fontSize: 11),
          ),
          if ((s['averagePriceUgx'] as num? ?? 0).toInt() > 0)
            Text(
              _money(s['averagePriceUgx']),
              style: const TextStyle(color: Colors.white54, fontSize: 11),
            ),
        ],
      ),
    );
  }

  Widget _opportunity(Map<String, dynamic> o) => _card(
    Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Icon(Icons.lightbulb, color: Color(0xFFF97316)),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _pretty(o['type']),
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              Text(
                o['description']?.toString() ?? '',
                style: const TextStyle(color: Colors.white60, fontSize: 12),
              ),
              Text(
                '${((o['confidence'] as num?)?.toDouble() ?? 0) * 100 ~/ 1}% confidence',
                style: const TextStyle(color: Color(0xFF50E3C2), fontSize: 11),
              ),
            ],
          ),
        ),
      ],
    ),
    item: o,
    kind: 'opportunity',
  );
  Widget _comparison(Map<String, dynamic> c) => _card(
    Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                c['product']?.toString() ?? '',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
            _tag(_pretty(c['position'])),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          'Ours ${_money(c['ourPriceUgx'])} • Market ${_money(c['marketAverageUgx'])}',
          style: const TextStyle(color: Colors.white60),
        ),
        Text(
          c['recommendation']?.toString() ?? '',
          style: const TextStyle(color: Color(0xFF50E3C2), fontSize: 12),
        ),
      ],
    ),
    item: c,
    kind: 'comparison',
  );
  Widget _listing(Map<String, dynamic> l) => _card(
    Row(
      children: [
        _tag(l['source']?.toString() ?? ''),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                l['title']?.toString() ?? '',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              Text(
                '${l['seller'] ?? ''} • ${l['category'] ?? ''}',
                style: const TextStyle(color: Colors.white38, fontSize: 11),
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        Text(
          _money(l['priceUgx']),
          style: const TextStyle(
            color: Color(0xFF50E3C2),
            fontWeight: FontWeight.w700,
            fontSize: 12,
          ),
        ),
      ],
    ),
    item: l,
    kind: 'listing',
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
  Widget _empty(String text) => Container(
    padding: const EdgeInsets.all(16),
    decoration: _box(),
    child: Text(
      text,
      style: TextStyle(color: Colors.white.withValues(alpha: .45)),
    ),
  );
  Widget _card(
    Widget child, {
    required Map<String, dynamic> item,
    required String kind,
  }) => InkWell(
    borderRadius: BorderRadius.circular(17),
    onTap: () => showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => InsightSheet(item: item, kind: kind),
    ),
    child: Container(
      margin: const EdgeInsets.only(bottom: 9),
      padding: const EdgeInsets.all(14),
      decoration: _box(),
      child: Row(
        children: [
          Expanded(child: child),
          const SizedBox(width: 8),
          const Icon(Icons.chevron_right, size: 18, color: Colors.white38),
        ],
      ),
    ),
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
  BoxDecoration _box({bool accent = false}) => BoxDecoration(
    color: const Color(0xFF121616),
    borderRadius: BorderRadius.circular(17),
    border: accent
        ? Border.all(color: const Color(0xFF50E3C2).withValues(alpha: .25))
        : null,
  );
  String _money(dynamic value) {
    final n = (value as num?)?.toInt() ?? 0;
    return 'UGX ${n.toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => ',')}';
  }

  String _pretty(dynamic value) => (value?.toString() ?? '')
      .replaceAll('_', ' ')
      .toLowerCase()
      .split(' ')
      .where((e) => e.isNotEmpty)
      .map((e) => '${e[0].toUpperCase()}${e.substring(1)}')
      .join(' ');
}
