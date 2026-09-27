import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';

class MeetingCommitmentsScreen extends StatefulWidget {
  const MeetingCommitmentsScreen({super.key});

  @override
  State<MeetingCommitmentsScreen> createState() =>
      _MeetingCommitmentsScreenState();
}

class _MeetingCommitmentsScreenState extends State<MeetingCommitmentsScreen> {
  List<Map<String, dynamic>> items = const [];
  bool busy = false;

  @override
  void initState() {
    super.initState();
    refresh();
  }

  Future<void> refresh() async {
    if (busy) return;
    setState(() => busy = true);
    try {
      final value = await AgentChannel.meetingCommitments();
      if (mounted) setState(() => items = value);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> confirm(Map<String, dynamic> item) async {
    final phone = TextEditingController();
    final zone = TextEditingController(text: 'Africa/Kampala');
    DateTime? chosen;
    bool customer = false;
    int leadMinutes = 5;
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Confirm agreed meeting'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'Use this only after both people agreed. Amara will use the exact phone and time you enter.',
                ),
                TextField(
                  controller: phone,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                    labelText: 'Customer phone (+256…)',
                  ),
                ),
                TextField(
                  controller: zone,
                  decoration: const InputDecoration(labelText: 'IANA timezone'),
                ),
                TextButton(
                  onPressed: () async {
                    final date = await showDatePicker(
                      context: context,
                      firstDate: DateTime.now(),
                      lastDate: DateTime.now().add(const Duration(days: 365)),
                      initialDate: DateTime.now(),
                    );
                    if (date == null || !context.mounted) return;
                    final time = await showTimePicker(
                      context: context,
                      initialTime: TimeOfDay.now(),
                    );
                    if (time != null) {
                      setDialogState(
                        () => chosen = DateTime(
                          date.year,
                          date.month,
                          date.day,
                          time.hour,
                          time.minute,
                        ),
                      );
                    }
                  },
                  child: Text(
                    chosen == null
                        ? 'Choose agreed date and time'
                        : chosen.toString(),
                  ),
                ),
                SwitchListTile(
                  value: customer,
                  title: Text('Send customer a $leadMinutes-minute reminder'),
                  onChanged: (value) => setDialogState(() => customer = value),
                ),
                DropdownButtonFormField<int>(
                  initialValue: leadMinutes,
                  decoration: const InputDecoration(
                    labelText: 'Reminder lead time',
                  ),
                  items: [5, 10, 15, 30]
                      .map(
                        (minutes) => DropdownMenuItem(
                          value: minutes,
                          child: Text('$minutes minutes before'),
                        ),
                      )
                      .toList(),
                  onChanged: (value) =>
                      setDialogState(() => leadMinutes = value ?? 5),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Close'),
            ),
            FilledButton(
              onPressed: chosen == null
                  ? null
                  : () => Navigator.pop(context, true),
              child: const Text('Confirm agreement'),
            ),
          ],
        ),
      ),
    );
    if (accepted == true && chosen != null) {
      try {
        await AgentChannel.confirmMeetingCommitment(
          id: '${item['id']}',
          localDateTime:
              '${chosen!.year.toString().padLeft(4, '0')}-${chosen!.month.toString().padLeft(2, '0')}-${chosen!.day.toString().padLeft(2, '0')}T${chosen!.hour.toString().padLeft(2, '0')}:${chosen!.minute.toString().padLeft(2, '0')}:00',
          timezone: zone.text.trim(),
          target: phone.text.trim(),
          remindCustomer: customer,
          reminderMinutes: leadMinutes,
        );
        await refresh();
      } catch (error) {
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text('$error')));
        }
      }
    }
    phone.dispose();
    zone.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Meetings and reminders'),
      actions: [
        IconButton(onPressed: refresh, icon: const Icon(Icons.refresh)),
      ],
    ),
    body: items.isEmpty
        ? Center(
            child: Text(busy ? 'Loading…' : 'No meeting requests recorded'),
          )
        : ListView.builder(
            itemCount: items.length,
            itemBuilder: (context, index) {
              final item = items[index];
              final status = '${item['status'] ?? ''}';
              final at = (item['agreedAt'] as num?)?.toInt() ?? 0;
              final reminders = (item['reminders'] as List?) ?? const [];
              return ListTile(
                title: Text('${item['subject'] ?? 'Meeting'}'),
                subtitle: Text(
                  [
                    status,
                    if (item['missed'] == true) 'Missed — no verified reminder',
                    if (at > 0)
                      DateTime.fromMillisecondsSinceEpoch(
                        at,
                      ).toLocal().toString(),
                    if ('${item['timezone'] ?? ''}'.isNotEmpty)
                      '${item['timezone']}',
                    if (reminders.isNotEmpty)
                      'Reminder: ${(reminders.last as Map)['state']}',
                    if ('${item['nextAction'] ?? ''}'.isNotEmpty)
                      '${item['nextAction']}',
                  ].join(' · '),
                ),
                isThreeLine: true,
                onTap: status == 'clarification_needed'
                    ? () => confirm(item)
                    : null,
                trailing: status == 'cancelled' || status == 'completed'
                    ? null
                    : IconButton(
                        tooltip: 'Cancel commitment',
                        icon: const Icon(Icons.event_busy_outlined),
                        onPressed: () async {
                          final ok = await showDialog<bool>(
                            context: context,
                            builder: (context) => AlertDialog(
                              title: const Text('Cancel this commitment?'),
                              content: const Text(
                                'This stops its pending reminder. It does not send a cancellation message.',
                              ),
                              actions: [
                                TextButton(
                                  onPressed: () =>
                                      Navigator.pop(context, false),
                                  child: const Text('Keep'),
                                ),
                                FilledButton(
                                  onPressed: () => Navigator.pop(context, true),
                                  child: const Text('Cancel commitment'),
                                ),
                              ],
                            ),
                          );
                          if (ok == true) {
                            await AgentChannel.cancelMeetingCommitment(
                              '${item['id']}',
                            );
                            await refresh();
                          }
                        },
                      ),
              );
            },
          ),
  );
}
