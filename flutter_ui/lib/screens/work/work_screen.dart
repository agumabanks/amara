import '../../widgets/task_swipe.dart';
import 'dart:async';
import 'package:flutter/services.dart';

import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';

class WorkScreen extends StatefulWidget {
  const WorkScreen({super.key});
  @override
  State<WorkScreen> createState() => _WorkScreenState();
}

class _WorkScreenState extends State<WorkScreen> with WidgetsBindingObserver {
  Map<String, dynamic> _mission = const {};
  Map<String, dynamic> _autonomy = const {};
  bool _loading = true;
  bool _refreshing = false;
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
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
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final values = await Future.wait([
        AgentChannel.missionControl(),
        AgentChannel.autonomousDashboard(),
      ]);
      if (mounted) {
        setState(() {
          _mission = values[0];
          _autonomy = values[1];
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _bulkClose(List<Map<String, dynamic>> items) async {
    final keys = items.take(1000).map((e) => '${e['key']}').toList();
    final disposition = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Resolve ${keys.length} holds?'),
        content: const Text(
          'This closes the currently listed holds and retains their history. It will not send messages, verify delivery, or stop pending and running tasks.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'no_longer_needed'),
            child: const Text('No longer needed'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, 'handled_elsewhere'),
            child: const Text('Handled elsewhere'),
          ),
        ],
      ),
    );
    if (disposition == null || !mounted) return;
    try {
      final count = await const MethodChannel('com.sanaa.agent/core')
          .invokeMethod<int>('closeWorkReviews', {
            'keys': keys,
            'disposition': disposition,
          });
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '${count ?? 0} holds closed. History retained; no messages sent.',
          ),
        ),
      );
      await _refresh();
    } catch (_) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Could not finish bulk resolution. Refresh before retrying.',
            ),
          ),
        );
    }
  }

  Future<void> _closeReview(Map<String, dynamic> item) async {
    final disposition = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Resolve this hold?'),
        content: const Text(
          'Check the WhatsApp conversation first. Closing this hold won’t send a message or mark delivery as verified. The review record will be kept.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Keep waiting'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'no_longer_needed'),
            child: const Text('No reply needed'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'handled_elsewhere'),
            child: const Text('I handled it'),
          ),
        ],
      ),
    );
    if (disposition == null || !mounted) return;
    try {
      final closed = await AgentChannel.closeWorkReview(
        item['key'] as String,
        disposition,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            closed
                ? 'Hold closed. No message was sent.'
                : 'This hold changed. Please check the refreshed list.',
          ),
        ),
      );
      await _refresh();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('I couldn’t close this hold. Please try again.'),
          ),
        );
      }
    }
  }

  List<Map<String, dynamic>> _maps(dynamic value) =>
      (value as List? ?? const [])
          .whereType<Map>()
          .map((e) => e.cast<String, dynamic>())
          .toList();

  @override
  Widget build(BuildContext context) {
    final loop =
        (_autonomy['loop'] as Map?)?.cast<String, dynamic>() ?? const {};
    final queue =
        (_autonomy['queue'] as Map?)?.cast<String, dynamic>() ?? const {};
    final queueItems = _maps(queue['items']);
    final reviewItems = _maps(queue['needsReview']);
    final learning = (_autonomy['learning'] as Map?) ?? const {};
    final errors = _maps(learning['errors']);
    final schedules = [
      ..._maps(_mission['systemSchedules']),
      ..._maps(_mission['schedules']),
    ];
    final templates = _maps(_mission['templates']);
    final learnedSuggestions = _maps(_mission['learnedRoutineSuggestions']);
    final skills = _maps(_mission['skills']);
    final approvals = _maps(_mission['approvals']);
    final findings = _maps(_mission['findings']);
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'My work',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
        actions: [
          IconButton(
            tooltip: 'Check for work now',
            onPressed: () async {
              await AgentChannel.wakeAutonomousLoop();
              await _refresh();
            },
            icon: const Icon(Icons.bolt, color: Color(0xFF50E3C2)),
          ),
          IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(18, 4, 18, 40),
                children: [
                  _loopCard(loop, queueItems.length),
                  const SizedBox(height: 20),
                  if (reviewItems.isNotEmpty) ...[
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton.icon(
                        onPressed: () => _bulkClose(reviewItems),
                        icon: const Icon(Icons.done_all),
                        label: Text(
                          'Resolve listed holds (${reviewItems.length})',
                        ),
                      ),
                    ),
                    ExpansionTile(
                      title: Text('Needs review (${reviewItems.length})'),
                      subtitle: const Text(
                        'Swipe to resolve a hold. Check the conversation first.',
                      ),
                      children: reviewItems
                          .map(
                            (item) => TaskSwipe(
                              id: 'review-${item['key']}',
                              onLeft: () => _closeReview(item),
                              onRight: () => _closeReview(item),
                              rightLabel: 'Resolve',
                              child: ExpansionTile(
                                leading: const Icon(
                                  Icons.info_outline,
                                  color: Colors.orange,
                                ),
                                title: Text(
                                  (item['conversation'] as String?)
                                              ?.isNotEmpty ==
                                          true
                                      ? item['conversation'] as String
                                      : _pretty(item['kind']),
                                ),
                                subtitle: Text(_dateTime(item['reviewAt'])),
                                childrenPadding: const EdgeInsets.fromLTRB(
                                  16,
                                  0,
                                  16,
                                  16,
                                ),
                                children: [
                                  if ((item['message'] as String?)
                                          ?.isNotEmpty ==
                                      true)
                                    Text(
                                      'Customer message: ${item['message']}',
                                    ),
                                  const SizedBox(height: 8),
                                  Text(
                                    '${item['reason'] ?? 'Check the conversation and delivery status.'}',
                                  ),
                                  const SizedBox(height: 8),
                                  TextButton(
                                    onPressed: () => _closeReview(item),
                                    child: const Text('Resolve this hold'),
                                  ),
                                ],
                              ),
                            ),
                          )
                          .toList(),
                    ),
                    const SizedBox(height: 20),
                  ],
                  _heading('TASK QUEUE', queueItems.length),
                  if (queueItems.isEmpty)
                    _empty(
                      'Nothing is waiting. I’ll check for new work automatically.',
                    )
                  else
                    ...queueItems.map(_queueCard),
                  const SizedBox(height: 20),
                  _heading('SCHEDULED & RECURRING', schedules.length),
                  ...schedules.map(_scheduleCard),
                  const SizedBox(height: 20),
                  _heading('WORKFLOW TEMPLATES', templates.length),
                  ...templates.map(_templateCard),
                  const SizedBox(height: 20),
                  _heading('SKILLS & COMMANDS', skills.length),
                  if (skills.isEmpty)
                    _empty('No reusable skills installed')
                  else
                    ...skills.map(_skillCard),
                  const SizedBox(height: 20),
                  _heading(
                    'LEARNED ROUTINE SUGGESTIONS',
                    learnedSuggestions.length,
                  ),
                  if (learnedSuggestions.isEmpty)
                    _empty(
                      'Suggestions appear after the same safe work succeeds on at least 3 different days.',
                    )
                  else
                    ...learnedSuggestions.map(_learnedSuggestionCard),
                  const SizedBox(height: 20),
                  _heading('APPROVALS', approvals.length),
                  if (approvals.isEmpty)
                    _empty('No pending approvals')
                  else
                    ...approvals.map(_approvalCard),
                  const SizedBox(height: 20),
                  _heading('FINDINGS', findings.length),
                  if (findings.isEmpty)
                    _empty('No open findings')
                  else
                    ...findings.map(_findingCard),
                  ExpansionTile(
                    title: Text('ERROR HISTORY (${errors.length})'),
                    subtitle: const Text(
                      'Persistent failures and repeat counts — not automatically fixed',
                    ),
                    children: errors
                        .map(
                          (error) => ListTile(
                            title: Text(
                              '${error['action']} · ${error['count']} occurrences',
                            ),
                            subtitle: Text(
                              '${error['error']}\nLast seen: ${DateTime.fromMillisecondsSinceEpoch((error['lastSeen'] as num).toInt()).toLocal()}',
                            ),
                            isThreeLine: true,
                          ),
                        )
                        .toList(),
                  ),
                ],
              ),
            ),
    );
  }

  Widget _loopCard(Map<String, dynamic> loop, int queued) {
    final running = loop['running'] == true;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: _box(accent: running),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                running ? Icons.hub : Icons.pause_circle_outline,
                color: running ? const Color(0xFF50E3C2) : Colors.orange,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  running
                      ? (loop['ownerOn'] == false
                            ? 'Amara is off'
                            : 'I’m keeping track of work')
                      : 'Work is paused',
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
              ),
              _pill(loop['state']?.toString() ?? 'UNKNOWN'),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(child: _metric('${loop['cycleCount'] ?? 0}', 'Cycles')),
              Expanded(child: _metric('$queued', 'Queued')),
              Expanded(child: _metric('${loop['itemsExecuted'] ?? 0}', 'Done')),
              Expanded(child: _metric('${loop['itemsFailed'] ?? 0}', 'Failed')),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Last wake: ${_pretty(loop['lastWakeReason'])}',
            style: const TextStyle(color: Colors.white60, fontSize: 12),
          ),
          Text(
            loop['lastSummary']?.toString() ??
                'I’ll show the latest update here.',
            style: const TextStyle(color: Colors.white38, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Future<void> _archivePending(Map<String, dynamic> item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Archive this task?'),
        content: const Text(
          'Remove this pending task from the queue. Its record stays available. Future scheduled runs are unchanged.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Keep task'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Archive task'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      final changed = await AgentChannel.archivePendingWork('${item['key']}');
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            changed
                ? 'Task archived.'
                : 'Task already started or changed. Refresh to review.',
          ),
        ),
      );
      await _refresh();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Could not archive this task. Try again.'),
          ),
        );
      }
    }
  }

  Widget _queueCard(Map<String, dynamic> item) {
    final requirements = (item['requires'] as List? ?? const []).join(', ');
    final payload = item['payload']?.toString() ?? '{}';
    return TaskSwipe(
      id: 'pending-${item['key']}',
      onLeft: () => _archivePending(item),
      onRight: () => _archivePending(item),
      rightLabel: 'Archive',
      child: Card(
        color: const Color(0xFF121616),
        margin: const EdgeInsets.only(bottom: 9),
        child: ExpansionTile(
          leading: const Icon(Icons.pending_actions, color: Color(0xFF50E3C2)),
          title: Text(
            _pretty(item['kind']),
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          subtitle: Text(
            '${_pretty(item['domain'])} • ${_pretty(item['risk'])} risk • attempt ${item['attempt']}',
            style: const TextStyle(color: Colors.white54, fontSize: 12),
          ),
          trailing: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                'VALUE ${(item['valueKes'] as num?)?.toStringAsFixed(0) ?? '0'}',
                style: const TextStyle(color: Color(0xFF50E3C2), fontSize: 11),
              ),
              const Icon(Icons.expand_more, color: Colors.white38),
            ],
          ),
          childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          children: [
            _detailRow('Queue key', item['key']),
            _detailRow('Created', _dateTime(item['createdAt'])),
            _detailRow(
              'Timing',
              item['due'] == true
                  ? 'Due now; device and policy checks still apply'
                  : 'Waiting until ${_dateTime(item['notBefore'])}',
            ),
            _detailRow('Deadline', _dateTime(item['deadline'])),
            _detailRow(
              'Expected phone time',
              '${item['estimatedScreenSeconds'] ?? 0} seconds',
            ),
            _detailRow(
              'Urgency half-life',
              '${item['urgencyHalfLifeHours'] ?? 0} hours',
            ),
            _detailRow(
              'Needs',
              requirements.isEmpty ? 'No special capability' : requirements,
            ),
            _detailRow(
              'Task input',
              payload == '{}' ? 'No additional input' : payload,
            ),
            TextButton.icon(
              onPressed: () => _archivePending(item),
              icon: const Icon(Icons.archive_outlined),
              label: const Text('Archive task'),
            ),
            const SizedBox(height: 6),
            const Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Value is a scheduling priority—not money earned.',
                style: TextStyle(color: Colors.white30, fontSize: 11),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _detailRow(String label, dynamic value) => Padding(
    padding: const EdgeInsets.only(top: 7),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 126,
          child: Text(
            label,
            style: const TextStyle(color: Colors.white38, fontSize: 12),
          ),
        ),
        Expanded(
          child: SelectableText(
            value?.toString() ?? 'Not set',
            style: const TextStyle(color: Colors.white70, fontSize: 12),
          ),
        ),
      ],
    ),
  );

  String _dateTime(dynamic millis) {
    final value = millis is num ? millis.toInt() : int.tryParse('$millis');
    if (value == null || value <= 0) return 'Not set';
    final date = DateTime.fromMillisecondsSinceEpoch(value).toLocal();
    return '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')} '
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }

  Widget _scheduleCard(Map<String, dynamic> schedule) {
    final enabled = schedule['enabled'] == true;
    final isOwner = schedule['instruction'] != null;
    return _card(
      Row(
        children: [
          Icon(
            enabled ? Icons.schedule : Icons.event_busy,
            color: enabled ? const Color(0xFF50E3C2) : Colors.white30,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  (schedule['name'] ??
                          schedule['taskText'] ??
                          schedule['instruction'] ??
                          'Recurring task')
                      .toString(),
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                Text(
                  (schedule['rule'] ?? '').toString(),
                  style: const TextStyle(color: Colors.white54, fontSize: 12),
                ),
                if (schedule['authority'] != null)
                  Text(
                    schedule['authority'].toString(),
                    style: const TextStyle(
                      color: Color(0xFFF97316),
                      fontSize: 11,
                    ),
                  ),
              ],
            ),
          ),
          if (isOwner)
            Switch(
              value: enabled,
              onChanged: (value) async {
                await AgentChannel.setScheduleEnabled(
                  (schedule['id'] as num).toInt(),
                  value,
                );
                _refresh();
              },
            )
          else
            _pill(enabled ? 'ON' : 'OFF'),
        ],
      ),
    );
  }

  Future<void> _runTemplate(Map<String, dynamic> template) async {
    final target = TextEditingController();
    final accepted = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Run ${template['name']}'),
        content: TextField(
          controller: target,
          decoration: const InputDecoration(
            labelText: 'Target or brief (optional)',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Start workflow'),
          ),
        ],
      ),
    );
    if (accepted != true || !mounted) return;
    final outcome = await AgentChannel.runDepartmentWorkflow(
      workflowId: template['id']?.toString() ?? '',
      target: target.text.trim(),
    );
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          outcome['state'] == 'awaiting_decision'
              ? outcome['question']?.toString() ?? 'Workflow needs a decision'
              : 'Workflow ${outcome['state'] ?? 'finished'}',
        ),
      ),
    );
    _refresh();
  }

  Widget _templateCard(Map<String, dynamic> template) => Card(
    color: const Color(0xFF121616),
    child: ExpansionTile(
      leading: const Icon(
        Icons.account_tree_outlined,
        color: Color(0xFF50E3C2),
      ),
      title: Text(
        template['name']?.toString() ?? 'Workflow',
        style: const TextStyle(fontWeight: FontWeight.w700),
      ),
      subtitle: Text(
        '${(template['steps'] as List?)?.length ?? 0} steps • ${template['classification']}',
        style: const TextStyle(color: Colors.white54, fontSize: 12),
      ),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: Text(
            'Inputs: ${(template['inputs'] as List? ?? const []).join(', ')}',
            style: const TextStyle(color: Colors.white60),
          ),
        ),
        const SizedBox(height: 6),
        Align(
          alignment: Alignment.centerLeft,
          child: Text(
            'Flow: ${(template['steps'] as List? ?? const []).map(_pretty).join(' → ')}',
            style: const TextStyle(color: Colors.white38, fontSize: 12),
          ),
        ),
        if ((template['approvals'] as List? ?? const []).isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Approval gates: ${(template['approvals'] as List).join(', ')}',
                style: const TextStyle(color: Color(0xFFF97316), fontSize: 12),
              ),
            ),
          ),
        const SizedBox(height: 10),
        Align(
          alignment: Alignment.centerRight,
          child: FilledButton.icon(
            onPressed: () => _runTemplate(template),
            icon: const Icon(Icons.play_arrow),
            label: const Text('Run'),
          ),
        ),
      ],
    ),
  );

  Widget _learnedSuggestionCard(Map<String, dynamic> suggestion) => _card(
    Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Icon(Icons.auto_awesome, color: Color(0xFF50E3C2)),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                suggestion['name']?.toString() ?? 'Learned routine',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              Text(
                suggestion['rule']?.toString() ?? '',
                style: const TextStyle(color: Colors.white60, fontSize: 12),
              ),
              Text(
                '${suggestion['evidenceCount'] ?? 0} verified observations • suggestion only',
                style: const TextStyle(color: Color(0xFFF97316), fontSize: 11),
              ),
            ],
          ),
        ),
      ],
    ),
  );

  Widget _skillCard(Map<String, dynamic> skill) => _card(
    Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.psychology_alt_outlined, color: Color(0xFF50E3C2)),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                skill['name']?.toString() ?? 'Reusable skill',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
            _pill('LEARNING'),
          ],
        ),
        const SizedBox(height: 9),
        SelectableText(
          'Say: “${skill['command'] ?? ''}”',
          style: const TextStyle(color: Colors.white70, fontSize: 12),
        ),
        const SizedBox(height: 4),
        Text(
          skill['learning']?.toString() ?? '',
          style: const TextStyle(color: Colors.white38, fontSize: 11),
        ),
      ],
    ),
  );

  Widget _approvalCard(Map<String, dynamic> a) => TaskSwipe(
    id: 'approval-${a['id']}',
    leftLabel: 'Reject',
    rightLabel: 'Approve',
    onLeft: () => _decide(a, false),
    onRight: () => _decide(a, true),
    child: _card(
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            a['description']?.toString() ?? 'Approval',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => _decide(a, false),
                  child: const Text('Reject'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton(
                  onPressed: () => _decide(a, true),
                  child: const Text('Approve'),
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );
  Widget _findingCard(Map<String, dynamic> f) => _card(
    Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          f['subject']?.toString() ?? 'Finding',
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        Text(
          f['issue']?.toString() ?? '',
          style: const TextStyle(color: Colors.white60),
        ),
        Text(
          f['recommendation']?.toString() ?? '',
          style: const TextStyle(color: Color(0xFF50E3C2), fontSize: 12),
        ),
      ],
    ),
  );
  Future<void> _decide(Map<String, dynamic> a, bool yes) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(yes ? 'Approve this task?' : 'Reject this task?'),
        content: SingleChildScrollView(
          child: Text('${a['description'] ?? 'Review this task'}'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(yes ? 'Approve task' : 'Reject task'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      final changed = await AgentChannel.decideApproval(
        (a['id'] as num).toInt(),
        yes,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            changed
                ? (yes
                      ? 'Approved. Execution still needs verification.'
                      : 'Rejected. Review record retained.')
                : 'This task changed. Refresh and review it again.',
          ),
        ),
      );
      await _refresh();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Decision could not be saved. Try again.'),
          ),
        );
      }
    }
  }

  Widget _heading(String text, int count) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Row(
      children: [
        Text(
          text,
          style: const TextStyle(
            color: Colors.white54,
            fontSize: 11,
            letterSpacing: 1.7,
            fontWeight: FontWeight.w800,
          ),
        ),
        const Spacer(),
        Text('$count', style: const TextStyle(color: Color(0xFF50E3C2))),
      ],
    ),
  );
  Widget _empty(String text) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 8),
    child: Text(text, style: const TextStyle(color: Colors.white38)),
  );
  Widget _card(Widget child) => Container(
    margin: const EdgeInsets.only(bottom: 9),
    padding: const EdgeInsets.all(14),
    decoration: _box(),
    child: child,
  );
  BoxDecoration _box({bool accent = false}) => BoxDecoration(
    color: const Color(0xFF121616),
    borderRadius: BorderRadius.circular(16),
    border: accent
        ? Border.all(color: const Color(0xFF50E3C2).withValues(alpha: .3))
        : null,
  );
  Widget _metric(String value, String label) => Column(
    children: [
      Text(
        value,
        style: const TextStyle(
          fontSize: 20,
          fontWeight: FontWeight.w800,
          color: Color(0xFF50E3C2),
        ),
      ),
      Text(label, style: const TextStyle(color: Colors.white38, fontSize: 10)),
    ],
  );
  Widget _pill(String text) => Container(
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
  String _pretty(dynamic value) => (value?.toString() ?? '')
      .replaceAll('_', ' ')
      .toLowerCase()
      .split(' ')
      .where((e) => e.isNotEmpty)
      .map((e) => '${e[0].toUpperCase()}${e.substring(1)}')
      .join(' ');
}
