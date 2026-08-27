import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';

class TasksTab extends StatefulWidget {
  const TasksTab({super.key});

  @override
  State<TasksTab> createState() => _TasksTabState();
}

class _TasksTabState extends State<TasksTab>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  Map<String, dynamic> _mission = const {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _refresh();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    try {
      final mission = await AgentChannel.missionControl();
      if (mounted) {
        setState(() {
          _mission = mission;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      backgroundColor: Colors.transparent,
      title: const Text('Tasks', style: TextStyle(fontWeight: FontWeight.w800)),
      actions: [
        IconButton(
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, color: Colors.white38),
        ),
      ],
      bottom: TabBar(
        controller: _tabController,
        indicatorColor: const Color(0xFF50E3C2),
        labelColor: const Color(0xFF50E3C2),
        unselectedLabelColor: Colors.white38,
        tabs: const [
          Tab(text: 'Approvals'),
          Tab(text: 'Recurring'),
          Tab(text: 'Activity'),
        ],
      ),
    ),
    body: _loading
        ? const Center(child: CircularProgressIndicator())
        : TabBarView(
            controller: _tabController,
            children: [_approvalsTab(), _recurringTab(), _activityTab()],
          ),
  );

  Widget _approvalsTab() {
    final approvals = (_mission['approvals'] as List?)?.cast<Map>() ?? const [];
    if (approvals.isEmpty) {
      return _emptyState(
        'No pending approvals',
        'Tasks waiting for your judgment will appear here.',
      );
    }
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView(
        padding: const EdgeInsets.all(18),
        children: approvals
            .map((a) => _approvalCard(a.cast<String, dynamic>()))
            .toList(),
      ),
    );
  }

  Widget _approvalCard(Map<String, dynamic> a) => Card(
    margin: const EdgeInsets.only(bottom: 10),
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            a['description']?.toString() ?? 'Approval request',
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
          ),
          const SizedBox(height: 4),
          Text(
            'Target: ${a['target'] ?? 'unknown'}',
            style: const TextStyle(color: Colors.white54, fontSize: 12),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => _decide(a['id'] as int? ?? 0, false),
                  child: const Text('Reject'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton(
                  onPressed: () => _decide(a['id'] as int? ?? 0, true),
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFF50E3C2),
                    foregroundColor: Colors.black,
                  ),
                  child: const Text('Approve'),
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );

  Future<void> _decide(int id, bool approve) async {
    await AgentChannel.decideApproval(id, approve);
    await _refresh();
  }

  Widget _recurringTab() {
    final schedules = (_mission['schedules'] as List?)?.cast<Map>() ?? const [];
    if (schedules.isEmpty) {
      return _emptyState(
        'No recurring tasks',
        'Commands you set to repeat will appear here.',
      );
    }
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView(
        padding: const EdgeInsets.all(18),
        children: [
          FilledButton.icon(
            onPressed: _showAddSchedule,
            icon: const Icon(Icons.add),
            label: const Text('Add recurring command'),
            style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFF50E3C2).withValues(alpha: 0.15),
              foregroundColor: const Color(0xFF50E3C2),
              minimumSize: const Size(0, 44),
            ),
          ),
          const SizedBox(height: 16),
          ...schedules.map((s) => _scheduleCard(s.cast<String, dynamic>())),
        ],
      ),
    );
  }

  Widget _scheduleCard(Map<String, dynamic> s) => Card(
    margin: const EdgeInsets.only(bottom: 8),
    child: ListTile(
      title: Text(
        s['command']?.toString() ?? '',
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
      ),
      subtitle: Text(
        '${s['schedule'] ?? 'unknown'} • ${s['enabled'] == true ? 'Active' : 'Paused'}',
        style: const TextStyle(color: Colors.white54, fontSize: 12),
      ),
      trailing: Switch(
        value: s['enabled'] == true,
        onChanged: (v) =>
            AgentChannel.setScheduleEnabled(s['id'] as int? ?? 0, v),
        activeThumbColor: const Color(0xFF50E3C2),
      ),
    ),
  );

  Future<void> _showAddSchedule() async {
    final commandController = TextEditingController();
    final scheduleController = TextEditingController();
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1A1F1E),
        title: const Text(
          'Add recurring command',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: commandController,
              decoration: const InputDecoration(
                hintText: 'e.g. Check Soko bookings',
              ),
              style: const TextStyle(color: Colors.white),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: scheduleController,
              decoration: const InputDecoration(
                hintText: 'e.g. every day at 9am',
              ),
              style: const TextStyle(color: Colors.white),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx),
            style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFF50E3C2),
              foregroundColor: Colors.black,
            ),
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }

  Widget _activityTab() {
    final activity = (_mission['activity'] as List?)?.cast<Map>() ?? const [];
    if (activity.isEmpty) {
      return _emptyState(
        'No recent activity',
        'Amara\'s completed work will appear here.',
      );
    }
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView(
        padding: const EdgeInsets.all(18),
        children: activity.map((a) {
          final item = a.cast<String, dynamic>();
          return Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 8,
                  height: 8,
                  margin: const EdgeInsets.only(top: 6),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: item['success'] == true
                        ? const Color(0xFF50E3C2)
                        : const Color(0xFFF97316),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item['summary']?.toString() ?? '',
                        style: const TextStyle(fontSize: 14),
                      ),
                      Text(
                        _formatTime(item['created_at']?.toString()),
                        style: const TextStyle(
                          color: Colors.white38,
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _emptyState(String title, String subtitle) => Center(
    child: Padding(
      padding: const EdgeInsets.all(40),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.inbox_outlined, color: Colors.white12, size: 48),
          const SizedBox(height: 16),
          Text(
            title,
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
          ),
          const SizedBox(height: 6),
          Text(
            subtitle,
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white38, fontSize: 13),
          ),
        ],
      ),
    ),
  );

  String _formatTime(String? date) {
    final parsed = DateTime.tryParse(date ?? '')?.toLocal();
    if (parsed == null) return '';
    return '${parsed.hour.toString().padLeft(2, '0')}:${parsed.minute.toString().padLeft(2, '0')}';
  }
}
