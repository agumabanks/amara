import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';

class JobsAnalysisScreen extends StatefulWidget {
  const JobsAnalysisScreen({super.key});
  @override
  State<JobsAnalysisScreen> createState() => _JobsAnalysisScreenState();
}

class _JobsAnalysisScreenState extends State<JobsAnalysisScreen> {
  Map<String, dynamic> dashboard = const {};
  Map<String, dynamic> mission = const {};
  bool loading = true;

  Map<String, dynamic> map(dynamic value) =>
      value is Map ? Map<String, dynamic>.from(value) : const {};
  List<Map<String, dynamic>> rows(dynamic value) => value is List
      ? value.whereType<Map>().map((e) => Map<String, dynamic>.from(e)).toList()
      : const [];

  @override
  void initState() {
    super.initState();
    refresh();
  }

  Future<void> refresh() async {
    setState(() => loading = true);
    try {
      final values = await Future.wait([
        AgentChannel.autonomousDashboard(),
        AgentChannel.missionControl(),
      ]);
      if (mounted) {
        setState(() {
          dashboard = values[0];
          mission = values[1];
        });
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Widget metric(String label, Object? value, IconData icon) => Card(
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Row(
        children: [
          Icon(icon),
          const SizedBox(width: 10),
          Expanded(child: Text(label)),
          Text(
            '$value',
            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
          ),
        ],
      ),
    ),
  );

  Widget empty(String text) => Padding(
    padding: const EdgeInsets.all(24),
    child: Center(child: Text(text)),
  );

  @override
  Widget build(BuildContext context) {
    final queue = map(dashboard['queue']);
    final counts = map(queue['counts']);
    final learning = map(dashboard['learning']);
    final totals = map(learning['totals']);
    final governor = map(dashboard['governor']);
    final moduleStats = map(dashboard['moduleStats']);
    final recent = rows(learning['recent']);
    final errors = rows(learning['errors']);
    final scheduled = [
      ...rows(mission['systemSchedules']),
      ...rows(mission['schedules']),
    ];
    final modules = <String, Map<String, int>>{};
    for (final row in recent) {
      final domain = '${row['domain'] ?? 'OTHER'}';
      final bucket = modules.putIfAbsent(
        domain,
        () => {'attempts': 0, 'successes': 0},
      );
      bucket['attempts'] = bucket['attempts']! + 1;
      if (row['success'] == true) {
        bucket['successes'] = bucket['successes']! + 1;
      }
    }
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Jobs and analysis'),
          actions: [
            IconButton(onPressed: refresh, icon: const Icon(Icons.refresh)),
          ],
          bottom: const TabBar(
            isScrollable: true,
            tabs: [
              Tab(text: 'Overview'),
              Tab(text: 'Jobs'),
              Tab(text: 'Modules'),
              Tab(text: 'Reports'),
            ],
          ),
        ),
        body: loading
            ? const Center(child: CircularProgressIndicator())
            : TabBarView(
                children: [
                  ListView(
                    padding: const EdgeInsets.all(12),
                    children: [
                      metric(
                        'All recorded attempts',
                        totals['attempts'] ?? 0,
                        Icons.analytics_outlined,
                      ),
                      metric(
                        'Reported successful tasks',
                        totals['successes'] ?? 0,
                        Icons.verified_outlined,
                      ),
                      metric(
                        'Due now',
                        queue['dueCount'] ?? 0,
                        Icons.play_circle_outline,
                      ),
                      metric(
                        'Needs review',
                        rows(queue['needsReview']).length,
                        Icons.report_outlined,
                      ),
                      metric(
                        'Failures today',
                        governor['failuresToday'] ?? 0,
                        Icons.error_outline,
                      ),
                      if ((governor['openBreakers'] as List?)?.isNotEmpty ==
                          true)
                        Card(
                          child: ListTile(
                            leading: const Icon(Icons.electric_bolt),
                            title: const Text('Paused modules'),
                            subtitle: Text(
                              (governor['openBreakers'] as List).join(', '),
                            ),
                          ),
                        ),
                      Card(
                        child: ListTile(
                          title: const Text('Safety state'),
                          subtitle: Text('${governor['state'] ?? 'Unknown'}'),
                          trailing: Text(
                            '${governor['screenSecondsToday'] ?? 0}s screen time',
                          ),
                        ),
                      ),
                    ],
                  ),
                  ListView(
                    padding: const EdgeInsets.all(12),
                    children: [
                      const Text(
                        'Queue',
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      Wrap(
                        spacing: 8,
                        children: counts.entries
                            .map(
                              (e) => Chip(label: Text('${e.key}: ${e.value}')),
                            )
                            .toList(),
                      ),
                      ...rows(queue['items']).map(
                        (job) => Card(
                          child: ListTile(
                            leading: Icon(
                              job['due'] == true
                                  ? Icons.play_arrow
                                  : Icons.schedule,
                            ),
                            title: Text('${job['kind']}'.replaceAll('_', ' ')),
                            subtitle: Text(
                              '${job['domain']} · attempt ${job['attempt']} · ${job['risk']} risk',
                            ),
                            trailing: Text(
                              job['due'] == true ? 'DUE' : 'WAITING',
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        'Schedules',
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      ...scheduled.map(
                        (job) => ListTile(
                          leading: Icon(
                            job['enabled'] == true
                                ? Icons.event_available
                                : Icons.event_busy,
                          ),
                          title: Text(
                            '${job['name'] ?? job['instruction'] ?? 'Scheduled job'}',
                          ),
                          subtitle: Text('${job['rule'] ?? ''}'),
                        ),
                      ),
                    ],
                  ),
                  modules.isEmpty && moduleStats.isEmpty
                      ? empty('No module activity recorded yet')
                      : ListView(
                          padding: const EdgeInsets.all(12),
                          children: [
                            if (moduleStats.isNotEmpty)
                              ...moduleStats.entries.map(
                                (entry) => Card(
                                  child: ListTile(
                                    title: Text(entry.key.replaceAll('_', ' ')),
                                    subtitle: Text('${entry.value}'),
                                  ),
                                ),
                              ),
                            ...modules.entries.map((entry) {
                              final attempts = entry.value['attempts']!;
                              final successes = entry.value['successes']!;
                              return Card(
                                child: Padding(
                                  padding: const EdgeInsets.all(14),
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        entry.key,
                                        style: const TextStyle(
                                          fontWeight: FontWeight.w800,
                                        ),
                                      ),
                                      const SizedBox(height: 8),
                                      LinearProgressIndicator(
                                        value: attempts == 0
                                            ? 0
                                            : successes / attempts,
                                      ),
                                      const SizedBox(height: 6),
                                      Text(
                                        '$successes reported successful of $attempts recent jobs',
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            }),
                          ],
                        ),
                  ListView(
                    padding: const EdgeInsets.all(12),
                    children: [
                      const Text(
                        'Recent job reports',
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      ...recent.map(
                        (row) => Card(
                          child: ListTile(
                            leading: Icon(
                              row['success'] == true
                                  ? Icons.check_circle_outline
                                  : Icons.warning_amber,
                            ),
                            title: Text(
                              '${row['action']}'.replaceAll('_', ' '),
                            ),
                            subtitle: Text(
                              '${row['details'] ?? ''}${('${row['error'] ?? ''}').isEmpty ? '' : '\n${row['error']}'}',
                            ),
                            trailing: Text('${row['screenSeconds'] ?? 0}s'),
                          ),
                        ),
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        'Repeated blockers',
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      if (errors.isEmpty)
                        empty('No repeated blockers recorded'),
                      ...errors.map(
                        (row) => Card(
                          child: ListTile(
                            title: Text(
                              '${row['action']}'.replaceAll('_', ' '),
                            ),
                            subtitle: Text('${row['error']}'),
                            trailing: Text('×${row['count']}'),
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
      ),
    );
  }
}
