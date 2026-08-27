import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../bridge/agent_channel.dart';

class MissionControlScreen extends StatefulWidget {
  const MissionControlScreen({super.key});

  @override
  State<MissionControlScreen> createState() => _MissionControlScreenState();
}

class _MissionControlScreenState extends State<MissionControlScreen> {
  Map<String, dynamic> _data = const {};
  bool _loading = true;

  List<Map<String, dynamic>> _items(String key) =>
      ((_data[key] as List?) ?? const [])
          .whereType<Map>()
          .map((value) => value.cast<String, dynamic>())
          .toList();

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final data = await AgentChannel.missionControl();
    await _loadCatalog();
    if (mounted) {
      setState(() {
        _data = data;
        _loading = false;
      });
    }
  }

  List<CapabilityLabel> _capabilities = const [];
  List<CommitmentSummary> _commitments = const [];

  Future<void> _loadCatalog() async {
    try {
      final capabilities = await AgentChannel.exposedCapabilities();
      final commitments = await AgentChannel.activeCommitments();
      if (!mounted) return;
      setState(() {
        _capabilities = capabilities;
        _commitments = commitments;
      });
    } on MissingPluginException {
      // Widget-test / preview environment without the native host.
    }
  }

  String? _capabilityLabel(String? capabilityId) {
    if (capabilityId == null) return null;
    for (final c in _capabilities) {
      if (c.id == capabilityId) return c.label;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final approvals = _items('approvals');
    final findings = _items('findings');
    final schedules = _items('schedules');
    final settings = ((_data['settings'] as Map?) ?? const {})
        .cast<String, dynamic>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Work control'),
        actions: [
          IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 40),
                children: [
                  _summary(
                    approvals.length,
                    findings.length,
                    schedules.where((item) => item['enabled'] == true).length,
                  ),
                  const SizedBox(height: 14),
                  _auditPolicy(settings),
                  const SizedBox(height: 22),
                  _heading('ACTIVE COMMITMENTS', _commitments.length),
                  if (_commitments.isEmpty)
                    const Text(
                      'No workflows are running right now.',
                      style: TextStyle(fontSize: 12, color: Colors.black54),
                    )
                  else
                    ..._commitments.map(
                      (c) => ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(
                          '${c.id} — ${c.phase} (step ${c.stepIndex})',
                        ),
                        subtitle: c.decisionQuestion.isEmpty
                            ? null
                            : Text(
                                c.decisionQuestion,
                                style: const TextStyle(fontSize: 12),
                              ),
                      ),
                    ),
                  const SizedBox(height: 22),
                  _heading('WAITING FOR YOU', approvals.length),
                  if (approvals.isEmpty)
                    _empty('No changes are waiting for approval.')
                  else
                    ...approvals.map(_approval),
                  const SizedBox(height: 22),
                  _heading('SHOP FINDINGS', findings.length),
                  if (findings.isEmpty)
                    _empty(
                      'No verified open findings yet. Ask Amara to audit Soko.',
                    )
                  else
                    ...findings.map(_finding),
                  const SizedBox(height: 22),
                  _heading('RECURRING WORK', schedules.length),
                  if (schedules.isEmpty)
                    _empty('No recurring owner instructions yet.')
                  else
                    ...schedules.map(_schedule),
                ],
              ),
            ),
    );
  }

  Widget _summary(int approvals, int findings, int enabledSchedules) =>
      Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: const Color(0xFF50E3C2).withValues(alpha: .08),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: const Color(0xFF50E3C2).withValues(alpha: .18),
          ),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceAround,
          children: [
            _metric('$approvals', 'Approvals'),
            _metric('$findings', 'Findings'),
            _metric('$enabledSchedules', 'Schedules'),
          ],
        ),
      );

  Widget _auditPolicy(Map<String, dynamic> settings) => Card(
    child: SwitchListTile(
      value: settings['proactiveReadOnlyAudits'] == true,
      onChanged: (value) async {
        if (await AgentChannel.setProactiveReadOnlyAudits(value)) {
          await _refresh();
        }
      },
      secondary: const Icon(Icons.manage_search, color: Color(0xFF50E3C2)),
      title: const Text(
        'Proactive read-only shop checks',
        style: TextStyle(fontWeight: FontWeight.w800),
      ),
      subtitle: Text(
        'Every 6 hours when the phone is idle. Never edits or sends. Quiet ${settings['quietHoursStart'] ?? '22:00'}–${settings['quietHoursEnd'] ?? '06:30'}.',
      ),
    ),
  );

  Widget _metric(String value, String label) => Column(
    children: [
      Text(
        value,
        style: const TextStyle(
          fontSize: 24,
          fontWeight: FontWeight.w900,
          color: Color(0xFF50E3C2),
        ),
      ),
      Text(label, style: const TextStyle(color: Colors.white54, fontSize: 12)),
    ],
  );

  Widget _heading(String title, int count) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Text(
      '$title  $count',
      style: const TextStyle(
        color: Colors.white38,
        letterSpacing: 1.5,
        fontWeight: FontWeight.w800,
        fontSize: 11,
      ),
    ),
  );

  Widget _empty(String value) => Card(
    child: Padding(
      padding: const EdgeInsets.all(18),
      child: Text(value, style: const TextStyle(color: Colors.white54)),
    ),
  );

  Widget _approval(Map<String, dynamic> item) => Card(
    margin: const EdgeInsets.only(bottom: 10),
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.approval_outlined, color: Color(0xFFF97316)),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item['target']?.toString() ?? 'Proposed change',
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                    if (_capabilityLabel(item['capability']?.toString()) !=
                        null)
                      Text(
                        _capabilityLabel(item['capability']?.toString())!,
                        style: const TextStyle(
                          fontSize: 11,
                          color: Colors.black54,
                        ),
                      ),
                  ],
                ),
              ),
              Text(
                item['risk']?.toString().replaceAll('_', ' ') ?? '',
                style: const TextStyle(color: Colors.white38, fontSize: 11),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            item['description']?.toString() ?? '',
            style: const TextStyle(color: Colors.white70, height: 1.4),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => _decide(item, false),
                  child: const Text('Reject'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton(
                  onPressed: () => _decide(item, true),
                  child: const Text('Approve'),
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );

  Future<void> _decide(Map<String, dynamic> item, bool approve) async {
    final changed = await AgentChannel.decideApproval(
      (item['id'] as num).toInt(),
      approve,
    );
    if (changed) await _refresh();
  }

  Widget _finding(Map<String, dynamic> item) => Card(
    margin: const EdgeInsets.only(bottom: 10),
    child: ExpansionTile(
      leading: Icon(
        Icons.flag_outlined,
        color: item['severity'] == 'high'
            ? const Color(0xFFF97316)
            : const Color(0xFF50E3C2),
      ),
      title: Text(
        item['subject']?.toString() ?? 'Finding',
        style: const TextStyle(fontWeight: FontWeight.w700),
      ),
      subtitle: Text(
        item['issue']?.toString() ?? '',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: Text(
            'Evidence: ${item['evidence'] ?? ''}\n\nRecommended: ${item['recommendation'] ?? ''}\n\nConfidence: ${(((item['confidence'] as num?)?.toDouble() ?? 0) * 100).round()}%',
            style: const TextStyle(color: Colors.white60, height: 1.4),
          ),
        ),
      ],
    ),
  );

  Widget _schedule(Map<String, dynamic> item) => Card(
    margin: const EdgeInsets.only(bottom: 10),
    child: SwitchListTile(
      value: item['enabled'] == true,
      onChanged: (value) async {
        if (await AgentChannel.setScheduleEnabled(
          (item['id'] as num).toInt(),
          value,
        )) {
          await _refresh();
        }
      },
      title: Text(
        item['taskText']?.toString() ?? '',
        style: const TextStyle(fontWeight: FontWeight.w700),
      ),
      subtitle: Text(
        '${item['rule'] ?? ''} · next ${_date(item['nextRunAt'])}',
        style: const TextStyle(color: Colors.white54),
      ),
    ),
  );

  String _date(dynamic value) {
    final epoch = (value as num?)?.toInt();
    if (epoch == null) return 'not scheduled';
    final date = DateTime.fromMillisecondsSinceEpoch(epoch).toLocal();
    return '${date.day}/${date.month} ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }
}
