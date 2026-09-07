import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../bridge/agent_channel.dart';

class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, this.initialMessage});
  final String? initialMessage;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> with TickerProviderStateMixin {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final List<_ChatMessage> _messages = [];
  String _status = 'Active';
  String _phase = 'idle';
  String _phaseDetail = 'Ready for the next thing';
  Map<String, dynamic>? _lastReceipt;
  bool _busy = false;
  bool _showScrollButton = false;
  bool _typing = false;
  late final Future<void> _historyReady;
  Timer? _statusTimer;

  @override
  void initState() {
    super.initState();
    _historyReady = _restoreHistory();
    _restoreRuntimeStatus();
    _statusTimer = Timer.periodic(
      const Duration(seconds: 3),
      (_) => _restoreRuntimeStatus(),
    );
    _scroll.addListener(_onScroll);
    final initial = widget.initialMessage;
    if (initial != null && initial.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _input.text = initial;
        _input.selection = TextSelection.collapsed(offset: initial.length);
      });
    }
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final atBottom =
        _scroll.position.pixels >= _scroll.position.maxScrollExtent - 80;
    if (_showScrollButton == atBottom) {
      setState(() => _showScrollButton = !atBottom);
    }
  }

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    _statusTimer?.cancel();
    super.dispose();
  }

  Future<void> _restoreHistory() async {
    try {
      final history = await AgentChannel.chatHistory();
      final receipt = await AgentChannel.latestTaskReceipt();
      if (!mounted) return;
      setState(() {
        _messages.clear();
        if (history.isEmpty) {
          _messages.add(
            _ChatMessage.amara(
              "Morning — what can I take off your plate?",
              time: DateTime.now(),
            ),
          );
        } else {
          _messages.addAll(
            history.map((item) {
              final msg = item['owner'] == true
                  ? _ChatMessage.owner(
                      item['text']?.toString() ?? '',
                      time: _parseTime(item['timestamp']),
                    )
                  : _ChatMessage.amara(
                      item['text']?.toString() ?? '',
                      time: _parseTime(item['timestamp']),
                    );
              return msg;
            }),
          );
        }
        if (receipt != null &&
            (receipt['steps'] as List?)?.isNotEmpty == true) {
          _lastReceipt = receipt;
        }
      });
      _goToLatest();
    } on PlatformException {
      if (!mounted) return;
      setState(() {
        _messages.add(
          _ChatMessage.amara(
            "Morning — what can I take off your plate?",
            time: DateTime.now(),
          ),
        );
      });
    }
  }

  Future<void> _restoreRuntimeStatus() async {
    try {
      final value = await AgentChannel.autonomyStatus();
      if (!mounted || _busy) return;
      final reportedPhase = value['phase']?.toString() ?? 'idle';
      final phase = value['active'] == true || value['blocked'] == true
          ? reportedPhase
          : 'idle';
      setState(() {
        _phase = phase;
        _phaseDetail =
            value['detail']?.toString() ?? 'Ready for the next thing';
        _status = switch (phase) {
          'blocked' || 'failed' => 'Needs attention',
          'complete' => 'Done',
          'idle' => 'Active',
          _ => 'Recovering…',
        };
      });
    } catch (_) {
      // The visible history still loads; never synthesize a successful state.
    }
  }

  DateTime _parseTime(dynamic value) {
    if (value == null) return DateTime.now();
    return DateTime.tryParse(value.toString())?.toLocal() ?? DateTime.now();
  }

  Future<void> _send() async {
    await _historyReady;
    if (!mounted) return;
    final text = _input.text.trim();
    if (text.isEmpty || _busy) {
      if (_busy && text.isNotEmpty) {
        setState(
          () => _messages.add(
            _ChatMessage.amara(
              "I'm still on the last thing — give me a moment.",
              time: DateTime.now(),
            ),
          ),
        );
        _goToLatest();
      }
      return;
    }
    _input.clear();
    final now = DateTime.now();
    setState(() {
      _busy = true;
      _typing = true;
      _status = 'Observing…';
      _phase = 'observe';
      _phaseDetail = 'Reading the phone and recent memory';
      _lastReceipt = null;
      _messages.add(_ChatMessage.owner(text, time: now));
    });
    _goToLatest();
    unawaited(_pollProgress());
    try {
      final result = await AgentChannel.submitTask(
        command: text,
        contactName: '',
        contactPhone: '',
        runAt: DateTime.now(),
      );
      final scheduled = result['status'] == 'scheduled';
      final queued = result['status'] == 'queued';
      final response = result['message']?.toString().trim();
      if (!mounted) return;
      setState(() {
        _busy = false;
        _typing = false;
        _status = result['success'] == true ? 'Done' : 'Needs attention';
        _phase = result['success'] == true
            ? 'complete'
            : (result['status'] == 'needs_owner' ? 'blocked' : 'failed');
        _phaseDetail = result['success'] == true
            ? 'Finished and verified'
            : 'Stopped safely or needs your input';
        _lastReceipt = result;
        _messages.add(
          _ChatMessage.amara(
            scheduled
                ? '⏰ Scheduled: ${response ?? text}'
                : queued
                ? '⚡ ${response ?? 'Work queued'}'
                : (response?.isNotEmpty == true
                      ? response!
                      : "I couldn't finish that one."),
            time: DateTime.now(),
            special: scheduled || queued,
          ),
        );
      });
      _goToLatest();
      if (result['success'] == true) {
        await Future<void>.delayed(const Duration(seconds: 2));
        if (mounted && !_busy) {
          setState(() {
            _status = 'Active';
            _phase = 'idle';
          });
        }
      }
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _typing = false;
        _status = 'Needs attention';
        _phase = 'failed';
        _phaseDetail = 'The task stopped unexpectedly';
        _messages.add(
          _ChatMessage.amara(
            "I couldn't finish that. ${error.message ?? 'Give me a moment and try again.'}",
            time: DateTime.now(),
          ),
        );
      });
      _goToLatest();
    }
  }

  Future<void> _pollProgress() async {
    while (mounted && _busy) {
      try {
        final value = await AgentChannel.autonomyStatus();
        if (mounted && _busy) {
          final phase = value['phase']?.toString() ?? 'observe';
          setState(() {
            _phase = phase;
            _phaseDetail = value['detail']?.toString() ?? _phaseDetail;
            _status = switch (phase) {
              'observe' => 'Observing…',
              'analyze' => 'Thinking…',
              'act' => 'Working…',
              'recover' => 'Adapting…',
              'report' => 'Checking…',
              _ => 'Working…',
            };
          });
        }
      } on PlatformException {
        // The task itself returns the authoritative result.
      }
      await Future.delayed(const Duration(milliseconds: 550));
    }
  }

  Future<void> _stop() async {
    if (!_busy) return;
    await AgentChannel.stopCurrentTask();
    if (mounted) {
      setState(() {
        _status = 'Stopping…';
        _phaseDetail = "I'll stop before the next phone action";
      });
    }
  }

  Future<void> _attach() async {
    if (_busy) return;
    try {
      final file = await AgentChannel.pickAttachment();
      if (!mounted || file == null) return;
      final destination = await showDialog<String>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: const Color(0xFF1A1F1E),
          title: Text(
            file['name']?.toString() ?? 'Attachment',
            style: const TextStyle(color: Colors.white),
          ),
          content: const Text(
            'What should Amara do with this file?',
            style: TextStyle(color: Colors.white70),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, 'chat'),
              child: const Text('Send to chat'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx, 'status'),
              child: const Text('Post as Status'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel'),
            ),
          ],
        ),
      );
      if (!mounted || destination == null) return;
      final target = TextEditingController();
      final caption = TextEditingController();
      final details = await showDialog<Map<String, String>>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: const Color(0xFF1A1F1E),
          title: Text(
            destination == 'status'
                ? 'Post WhatsApp Status'
                : 'Send WhatsApp attachment',
            style: const TextStyle(color: Colors.white),
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (destination == 'chat')
                TextField(
                  controller: target,
                  decoration: const InputDecoration(
                    labelText: 'Exact contact or group',
                    labelStyle: TextStyle(color: Colors.white54),
                  ),
                  style: const TextStyle(color: Colors.white),
                ),
              TextField(
                controller: caption,
                decoration: const InputDecoration(
                  labelText: 'Caption (optional)',
                  labelStyle: TextStyle(color: Colors.white54),
                ),
                maxLines: 3,
                style: const TextStyle(color: Colors.white),
              ),
              if (destination == 'status')
                const Padding(
                  padding: EdgeInsets.only(top: 12),
                  child: Text(
                    'This publishes to your WhatsApp Status audience.',
                    style: TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, {
                'target': target.text.trim(),
                'caption': caption.text.trim(),
              }),
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFF50E3C2),
                foregroundColor: Colors.black,
              ),
              child: Text(destination == 'status' ? 'Publish' : 'Send'),
            ),
          ],
        ),
      );
      target.dispose();
      caption.dispose();
      if (!mounted ||
          details == null ||
          (destination == 'chat' && details['target']!.isEmpty)) {
        return;
      }
      setState(() {
        _busy = true;
        _typing = true;
        _status = 'Working on it…';
        _messages.add(
          _ChatMessage.owner(
            destination == 'status'
                ? "Post ${file['name']} to my WhatsApp Status."
                : "Send ${file['name']} to ${details['target']}.",
            time: DateTime.now(),
          ),
        );
      });
      _goToLatest();
      final success = destination == 'status'
          ? await AgentChannel.postWhatsAppMediaStatus(
              uri: file['uri'].toString(),
              mimeType: file['mimeType']?.toString() ?? 'image/*',
              caption: details['caption'] ?? '',
            )
          : await AgentChannel.sendWhatsAppAttachment(
              uri: file['uri'].toString(),
              mimeType:
                  file['mimeType']?.toString() ?? 'application/octet-stream',
              target: details['target']!,
              caption: details['caption'] ?? '',
            );
      if (!mounted) return;
      setState(() {
        _busy = false;
        _typing = false;
        _status = 'Active';
        _messages.add(
          _ChatMessage.amara(
            success
                ? (destination == 'status'
                      ? 'Done. I posted the WhatsApp Status.'
                      : "Done. I sent the attachment to ${details['target']}.")
                : "I couldn't verify that WhatsApp completed the attachment action.",
            time: DateTime.now(),
          ),
        );
      });
      _goToLatest();
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _typing = false;
        _status = 'Active';
        _messages.add(
          _ChatMessage.amara(
            "I couldn't handle that attachment. ${error.message ?? ''}",
            time: DateTime.now(),
          ),
        );
      });
    }
  }

  void _goToLatest() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scroll.hasClients) return;
      _scroll.animateTo(
        _scroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOut,
      );
    });
  }

  void _scrollToBottom() {
    if (!_scroll.hasClients) return;
    _scroll.animateTo(
      _scroll.position.maxScrollExtent,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      toolbarHeight: 72,
      backgroundColor: const Color(0xFF080A0A),
      titleSpacing: 4,
      title: Row(
        children: [
          const _AmaraAvatar(size: 42),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Amara',
                style: TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
              ),
              const SizedBox(height: 2),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 180),
                child: Text(
                  _status,
                  key: ValueKey(_status),
                  style: TextStyle(
                    color: _busy
                        ? const Color(0xFFF97316)
                        : const Color(0xFF50E3C2),
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
      actions: [
        IconButton(
          tooltip: 'Stop current task',
          onPressed: _busy ? _stop : null,
          icon: Icon(
            Icons.stop_circle_outlined,
            color: _busy ? const Color(0xFFFF9B68) : Colors.white24,
          ),
        ),
      ],
    ),
    body: Column(
      children: [
        if (_busy)
          _ProcessRail(phase: _phase, detail: _phaseDetail, onStop: _stop),
        Expanded(
          child: Stack(
            children: [
              ListView(
                controller: _scroll,
                padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
                children: [
                  ..._buildMessageList(),
                  if (_messages.length <= 1)
                    _PromptIdeas(
                      onPick: (v) {
                        _input.text = v;
                        _input.selection = TextSelection.collapsed(
                          offset: v.length,
                        );
                      },
                    ),
                  if (!_busy && _lastReceipt != null)
                    _WorkReceipt(data: _lastReceipt!),
                ],
              ),
              if (_showScrollButton)
                Positioned(
                  right: 16,
                  bottom: 16,
                  child: FloatingActionButton.small(
                    onPressed: _scrollToBottom,
                    backgroundColor: const Color(0xFF1A1F1E),
                    child: const Icon(
                      Icons.keyboard_arrow_down,
                      color: Color(0xFF50E3C2),
                    ),
                  ),
                ),
            ],
          ),
        ),
        _MessageBar(
          controller: _input,
          busy: _busy,
          onSend: _send,
          onAttach: _attach,
        ),
      ],
    ),
  );

  List<Widget> _buildMessageList() {
    final widgets = <Widget>[];
    DateTime? lastDate;
    for (var i = 0; i < _messages.length; i++) {
      final msg = _messages[i];
      final msgDate = DateTime(msg.time.year, msg.time.month, msg.time.day);
      if (lastDate == null || msgDate != lastDate) {
        widgets.add(_DateSeparator(date: msg.time));
        lastDate = msgDate;
      }
      widgets.add(
        _MessageBubble(
          message: msg,
          onCopy: () => Clipboard.setData(ClipboardData(text: msg.text)),
        ),
      );
    }
    if (_typing) widgets.add(_TypingIndicator());
    return widgets;
  }
}

class _DateSeparator extends StatelessWidget {
  const _DateSeparator({required this.date});
  final DateTime date;
  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final yesterday = today.subtract(const Duration(days: 1));
    final dateOnly = DateTime(date.year, date.month, date.day);
    final label = dateOnly == today
        ? 'Today'
        : dateOnly == yesterday
        ? 'Yesterday'
        : '${date.day}/${date.month}/${date.year}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          decoration: BoxDecoration(
            color: const Color(0xFF151A19),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(
            label,
            style: const TextStyle(
              color: Colors.white38,
              fontSize: 11,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}

class _TypingIndicator extends StatefulWidget {
  @override
  State<_TypingIndicator> createState() => _TypingIndicatorState();
}

class _TypingIndicatorState extends State<_TypingIndicator>
    with TickerProviderStateMixin {
  late AnimationController _controller;
  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    )..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        const _AmaraAvatar(size: 30),
        const SizedBox(width: 8),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(
            color: const Color(0xFF18342F),
            borderRadius: BorderRadius.only(
              topLeft: const Radius.circular(18),
              topRight: const Radius.circular(18),
              bottomRight: const Radius.circular(18),
              bottomLeft: const Radius.circular(4),
            ),
          ),
          child: AnimatedBuilder(
            animation: _controller,
            builder: (_, __) => Row(
              mainAxisSize: MainAxisSize.min,
              children: List.generate(3, (i) {
                final delay = i * 0.2;
                final value = (((_controller.value + delay) % 1.0) * 2 - 1)
                    .abs();
                return Container(
                  margin: const EdgeInsets.symmetric(horizontal: 2),
                  width: 6,
                  height: 6,
                  decoration: BoxDecoration(
                    color: Color.lerp(
                      const Color(0xFF50E3C2),
                      const Color(0xFF18342F),
                      value,
                    )!,
                    shape: BoxShape.circle,
                  ),
                );
              }),
            ),
          ),
        ),
      ],
    ),
  );
}

class _ProcessRail extends StatelessWidget {
  const _ProcessRail({
    required this.phase,
    required this.detail,
    required this.onStop,
  });
  final String phase;
  final String detail;
  final Future<void> Function() onStop;
  @override
  Widget build(BuildContext context) {
    const phases = ['observe', 'analyze', 'act', 'recover', 'report'];
    const labels = ['Observe', 'Analyze', 'Act', 'Adapt', 'Report'];
    final active = phases.indexOf(phase).clamp(0, phases.length - 1);
    return Container(
      margin: const EdgeInsets.fromLTRB(14, 8, 14, 0),
      padding: const EdgeInsets.fromLTRB(16, 13, 10, 12),
      decoration: BoxDecoration(
        color: const Color(0xFF121817),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: const Color(0xFF50E3C2).withValues(alpha: .18),
        ),
      ),
      child: Column(
        children: [
          Row(
            children: [
              for (var index = 0; index < phases.length; index++) ...[
                _PhaseDot(
                  label: labels[index],
                  active: index == active,
                  done: index < active,
                ),
                if (index < phases.length - 1)
                  Expanded(
                    child: Container(
                      height: 1,
                      color: index < active
                          ? const Color(0xFF50E3C2)
                          : Colors.white12,
                    ),
                  ),
              ],
            ],
          ),
          const SizedBox(height: 11),
          Row(
            children: [
              Expanded(
                child: Text(
                  detail,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white60,
                    fontSize: 12.5,
                    height: 1.3,
                  ),
                ),
              ),
              TextButton.icon(
                onPressed: onStop,
                icon: const Icon(Icons.stop_circle_outlined, size: 18),
                label: const Text('Stop'),
                style: TextButton.styleFrom(
                  foregroundColor: const Color(0xFFFF9B68),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _PhaseDot extends StatelessWidget {
  const _PhaseDot({
    required this.label,
    required this.active,
    required this.done,
  });
  final String label;
  final bool active;
  final bool done;
  @override
  Widget build(BuildContext context) => Column(
    children: [
      AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        width: active ? 12 : 9,
        height: active ? 12 : 9,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: done || active ? const Color(0xFF50E3C2) : Colors.white24,
          boxShadow: active
              ? [
                  BoxShadow(
                    color: const Color(0xFF50E3C2).withValues(alpha: .45),
                    blurRadius: 10,
                  ),
                ]
              : null,
        ),
      ),
      const SizedBox(height: 5),
      Text(
        label,
        style: TextStyle(
          color: active ? Colors.white : Colors.white38,
          fontSize: 10,
          fontWeight: active ? FontWeight.w700 : FontWeight.w500,
        ),
      ),
    ],
  );
}

class _PromptIdeas extends StatelessWidget {
  const _PromptIdeas({required this.onPick});
  final ValueChanged<String> onPick;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(38, 8, 6, 20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'TRY SAYING',
          style: TextStyle(
            color: Colors.white30,
            fontSize: 10,
            letterSpacing: 1.8,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 9),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children:
              [
                    (
                      'Read every visible Soko product',
                      Icons.storefront_outlined,
                    ),
                    ('Check my WhatsApp groups', Icons.groups_outlined),
                    (
                      'Post a Soko product on TikTok',
                      Icons.video_call_outlined,
                    ),
                    ('What did you just do?', Icons.history_rounded),
                  ]
                  .map(
                    (idea) => ActionChip(
                      avatar: Icon(idea.$2, size: 17),
                      label: Text(idea.$1),
                      onPressed: () => onPick(idea.$1),
                      backgroundColor: const Color(0xFF151A19),
                      side: const BorderSide(color: Colors.white10),
                    ),
                  )
                  .toList(),
        ),
      ],
    ),
  );
}

class _WorkReceipt extends StatelessWidget {
  const _WorkReceipt({required this.data});
  final Map<String, dynamic> data;
  @override
  Widget build(BuildContext context) {
    final steps =
        (data['steps'] as List?)?.whereType<Map>().toList() ?? const [];
    final observation = data['observation']?.toString().trim() ?? '';
    final analysis = data['analysis']?.toString().trim() ?? '';
    if (observation.isEmpty && analysis.isEmpty && steps.isEmpty) {
      return const SizedBox.shrink();
    }
    return Container(
      margin: const EdgeInsets.fromLTRB(38, 4, 6, 14),
      decoration: BoxDecoration(
        color: const Color(0xFF111615),
        borderRadius: BorderRadius.circular(17),
        border: Border.all(color: Colors.white10),
      ),
      child: Theme(
        data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
        child: ExpansionTile(
          tilePadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 2),
          childrenPadding: const EdgeInsets.fromLTRB(15, 0, 15, 15),
          leading: Icon(
            data['success'] == true
                ? Icons.verified_outlined
                : Icons.info_outline,
            color: data['success'] == true
                ? const Color(0xFF50E3C2)
                : const Color(0xFFF97316),
          ),
          title: const Text(
            'How I handled it',
            style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
          ),
          subtitle: Text(
            '${steps.length} verified step${steps.length == 1 ? '' : 's'}',
            style: const TextStyle(color: Colors.white38, fontSize: 11),
          ),
          children: [
            if (observation.isNotEmpty)
              _ReceiptLine(
                icon: Icons.visibility_outlined,
                label: 'Observed',
                text: observation,
              ),
            if (analysis.isNotEmpty)
              _ReceiptLine(
                icon: Icons.psychology_outlined,
                label: 'Analyzed',
                text: analysis,
              ),
            for (final step in steps)
              _ReceiptLine(
                icon: step['success'] == true
                    ? Icons.check_circle_outline
                    : Icons.error_outline,
                label:
                    step['action']?.toString().replaceAll('_', ' ') ?? 'Action',
                text: step['result']?.toString() ?? '',
              ),
          ],
        ),
      ),
    );
  }
}

class _ReceiptLine extends StatelessWidget {
  const _ReceiptLine({
    required this.icon,
    required this.label,
    required this.text,
  });
  final IconData icon;
  final String label;
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 11),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 17, color: const Color(0xFF50E3C2)),
        const SizedBox(width: 10),
        Expanded(
          child: RichText(
            text: TextSpan(
              style: const TextStyle(
                color: Colors.white54,
                height: 1.35,
                fontSize: 12.5,
              ),
              children: [
                TextSpan(
                  text: '$label\n',
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                TextSpan(text: text),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message, this.onCopy});
  final _ChatMessage message;
  final VoidCallback? onCopy;
  @override
  Widget build(BuildContext context) {
    final owner = message.owner;
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: GestureDetector(
        onLongPress: onCopy,
        child: Row(
          mainAxisAlignment: owner
              ? MainAxisAlignment.end
              : MainAxisAlignment.start,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            if (!owner) ...[
              const _AmaraAvatar(size: 28),
              const SizedBox(width: 8),
            ],
            Flexible(
              child: Container(
                constraints: const BoxConstraints(maxWidth: 300),
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 10,
                ),
                decoration: BoxDecoration(
                  color: owner
                      ? const Color(0xFFF2F5F4)
                      : const Color(0xFF18342F),
                  borderRadius: BorderRadius.only(
                    topLeft: const Radius.circular(16),
                    topRight: const Radius.circular(16),
                    bottomLeft: Radius.circular(owner ? 16 : 3),
                    bottomRight: Radius.circular(owner ? 3 : 16),
                  ),
                  border: message.special
                      ? Border.all(color: const Color(0xFFF97316), width: 1.5)
                      : null,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      message.text,
                      style: TextStyle(
                        color: owner
                            ? const Color(0xFF080A0A)
                            : const Color(0xFFE7FAF5),
                        height: 1.35,
                        fontSize: 14.5,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          _formatTime(message.time),
                          style: TextStyle(
                            color: owner ? Colors.black38 : Colors.white38,
                            fontSize: 10,
                          ),
                        ),
                        if (owner && message.status != null) ...[
                          const SizedBox(width: 4),
                          Icon(
                            message.status == 'sent'
                                ? Icons.done
                                : message.status == 'delivered'
                                ? Icons.done_all
                                : Icons.access_time,
                            size: 12,
                            color: message.status == 'delivered'
                                ? const Color(0xFF50E3C2)
                                : Colors.white38,
                          ),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
            ),
            if (owner) const SizedBox(width: 36),
          ],
        ),
      ),
    );
  }

  String _formatTime(DateTime time) =>
      '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}

class _MessageBar extends StatelessWidget {
  const _MessageBar({
    required this.controller,
    required this.busy,
    required this.onSend,
    required this.onAttach,
  });
  final TextEditingController controller;
  final bool busy;
  final Future<void> Function() onSend;
  final Future<void> Function() onAttach;
  @override
  Widget build(BuildContext context) => SafeArea(
    top: false,
    child: Container(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
      decoration: const BoxDecoration(
        color: Color(0xFF101414),
        border: Border(top: BorderSide(color: Colors.white10)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          IconButton(
            tooltip: 'Attach a file',
            onPressed: busy ? null : onAttach,
            icon: const Icon(Icons.attach_file_rounded, color: Colors.white54),
          ),
          Expanded(
            child: TextField(
              controller: controller,
              minLines: 1,
              maxLines: 5,
              textCapitalization: TextCapitalization.sentences,
              textInputAction: TextInputAction.send,
              onSubmitted: (_) => onSend(),
              decoration: const InputDecoration(
                hintText: 'Message Amara',
                contentPadding: EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 12,
                ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          IconButton.filled(
            tooltip: 'Send',
            onPressed: onSend,
            style: IconButton.styleFrom(
              backgroundColor: const Color(0xFF50E3C2),
              foregroundColor: const Color(0xFF080A0A),
              minimumSize: const Size(48, 48),
            ),
            icon: Icon(busy ? Icons.more_horiz : Icons.send_rounded),
          ),
        ],
      ),
    ),
  );
}

class _AmaraAvatar extends StatelessWidget {
  const _AmaraAvatar({required this.size});
  final double size;
  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    alignment: Alignment.center,
    decoration: const BoxDecoration(
      color: Color(0xFF50E3C2),
      shape: BoxShape.circle,
    ),
    child: Text(
      'A',
      style: TextStyle(
        color: const Color(0xFF080A0A),
        fontSize: size * .46,
        fontWeight: FontWeight.w900,
      ),
    ),
  );
}

class _ChatMessage {
  const _ChatMessage(
    this.text, {
    required this.owner,
    this.special = false,
    required this.time,
    this.status,
  });
  factory _ChatMessage.amara(
    String text, {
    bool special = false,
    required DateTime time,
  }) => _ChatMessage(
    text,
    owner: false,
    special: special,
    time: time,
    status: 'delivered',
  );
  factory _ChatMessage.owner(String text, {required DateTime time}) =>
      _ChatMessage(text, owner: true, time: time, status: 'sent');
  final String text;
  final bool owner;
  final bool special;
  final DateTime time;
  final String? status;
}
