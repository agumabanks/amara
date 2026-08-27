import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';
import 'chat_screen.dart';

class ChatTab extends StatefulWidget {
  const ChatTab({super.key});

  @override
  State<ChatTab> createState() => _ChatTabState();
}

class _ChatTabState extends State<ChatTab> {
  Map<String, dynamic> _status = const {'phase': 'idle', 'detail': 'Ready'};
  bool _expanded = false;

  @override
  void initState() {
    super.initState();
    _pollStatus();
  }

  Future<void> _pollStatus() async {
    while (mounted) {
      try {
        final status = await AgentChannel.runtimeStatusSnapshot();
        if (mounted) setState(() => _status = status);
      } catch (_) {}
      await Future.delayed(const Duration(seconds: 2));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      backgroundColor: Colors.transparent,
      title: GestureDetector(
        onTap: () => setState(() => _expanded = !_expanded),
        child: Row(
          children: [
            Container(
              width: 10,
              height: 10,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _status['active'] == true
                    ? const Color(0xFF50E3C2)
                    : Colors.white24,
                boxShadow: _status['active'] == true
                    ? [
                        BoxShadow(
                          color: const Color(0xFF50E3C2).withValues(alpha: 0.5),
                          blurRadius: 8,
                        ),
                      ]
                    : null,
              ),
            ),
            const SizedBox(width: 10),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Amara',
                  style: TextStyle(fontWeight: FontWeight.w800, fontSize: 17),
                ),
                Text(
                  (_status['detail'] ?? 'Ready').toString().take(30),
                  style: const TextStyle(fontSize: 11, color: Colors.white38),
                ),
              ],
            ),
          ],
        ),
      ),
      actions: [
        IconButton(
          tooltip: 'Stop current task',
          onPressed: () => AgentChannel.stopCurrentTask(),
          icon: const Icon(Icons.stop_circle_outlined, color: Colors.white38),
        ),
      ],
    ),
    body: Column(
      children: [
        if (_expanded)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(18, 4, 18, 12),
            color: const Color(0xFF0D1111),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Phase: ${_status['phase'] ?? 'idle'}',
                  style: const TextStyle(
                    color: Color(0xFF50E3C2),
                    fontWeight: FontWeight.w600,
                    fontSize: 12,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  _status['detail']?.toString() ?? '',
                  style: const TextStyle(color: Colors.white54, fontSize: 12),
                ),
              ],
            ),
          ),
        const Expanded(child: ChatScreen()),
      ],
    ),
  );
}

extension on String {
  String take(int n) => length <= n ? this : '${substring(0, n)}…';
}
