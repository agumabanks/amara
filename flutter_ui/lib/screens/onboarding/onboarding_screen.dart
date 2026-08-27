import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../bridge/agent_channel.dart';
import '../dashboard/dashboard_screen.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});
  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen>
    with WidgetsBindingObserver {
  final _key = TextEditingController();
  PermissionState? _permissions;
  bool _busy = false, _keySaved = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _key.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      await AgentChannel.syncConfig();
      final values = await Future.wait([
        AgentChannel.permissionStatus(),
        AgentChannel.hasGroqKey(),
      ]);
      if (mounted) {
        setState(() {
          _permissions = values[0] as PermissionState;
          _keySaved = values[1] as bool;
        });
      }
    } on MissingPluginException {
      /* Android host is absent in widget previews. */
    }
  }

  Future<void> _testGroq() async {
    if (_key.text.trim().isNotEmpty) {
      await AgentChannel.saveGroqKey(_key.text.trim());
    }
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final reply = await AgentChannel.testGroq();
      if (mounted) {
        setState(() {
          _keySaved = true;
          _message = reply;
          _key.clear();
        });
      }
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() => _message = error.message ?? 'Connection failed');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _launch() async {
    await AgentChannel.startAgent();
    if (mounted) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const DashboardScreen()),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = _permissions;
    final ready = p?.ready == true && _keySaved;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 620),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(24, 32, 24, 40),
              children: [
                const _Brand(),
                const SizedBox(height: 36),
                Text(
                  'Let’s get Amara ready.',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 10),
                const Text(
                  'These permissions let your agent keep working when your Oppo screen is off.',
                  style: TextStyle(
                    color: Colors.white60,
                    height: 1.5,
                    fontSize: 16,
                  ),
                ),
                const SizedBox(height: 28),
                _PermissionCard(
                  number: '01',
                  title: 'Accessibility',
                  detail:
                      'On Oppo: Additional settings → Accessibility → Downloaded apps → Sanaa Agent.',
                  enabled: p?.accessibility ?? false,
                  action: AgentChannel.openAccessibility,
                ),
                _PermissionCard(
                  number: '02',
                  title: 'Keep alive',
                  detail:
                      'Choose “Allow” for battery optimisation, then allow background activity in ColorOS App management.',
                  enabled: p?.battery ?? false,
                  action: AgentChannel.openBattery,
                  critical: true,
                ),
                _PermissionCard(
                  number: '03',
                  title: 'Display over apps',
                  detail:
                      'Used only to show visual feedback while Amara carries out an action.',
                  enabled: p?.overlay ?? false,
                  action: AgentChannel.openOverlay,
                ),
                _PermissionCard(
                  number: '04',
                  title: 'Notification access',
                  detail:
                      'Lets Amara notice new WhatsApp messages without constantly polling.',
                  enabled: p?.notificationAccess ?? false,
                  action: AgentChannel.openNotificationAccess,
                ),
                _PermissionCard(
                  number: '05',
                  title: 'Send notifications',
                  detail:
                      'Reports completed work and asks you when human judgment is needed.',
                  enabled: p?.notifications ?? false,
                  action: AgentChannel.requestNotifications,
                ),
                const SizedBox(height: 18),
                _GroqCard(
                  controller: _key,
                  saved: _keySaved,
                  busy: _busy,
                  message: _message,
                  onTest: _testGroq,
                ),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: ready ? _launch : null,
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFFF97316),
                    foregroundColor: Colors.white,
                    disabledBackgroundColor: Colors.white12,
                    padding: const EdgeInsets.symmetric(vertical: 18),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  child: Text(
                    ready ? 'Start Amara' : 'Finish setup to continue',
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Brand extends StatelessWidget {
  const _Brand();
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: const Color(0xFF50E3C2),
          borderRadius: BorderRadius.circular(13),
        ),
        child: const Icon(Icons.auto_awesome, color: Color(0xFF080A0A)),
      ),
      const SizedBox(width: 12),
      const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'SANAA',
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w900,
              letterSpacing: 2,
            ),
          ),
          Text(
            'AGENT',
            style: TextStyle(
              color: Colors.white54,
              fontSize: 11,
              letterSpacing: 3,
            ),
          ),
        ],
      ),
    ],
  );
}

class _PermissionCard extends StatelessWidget {
  const _PermissionCard({
    required this.number,
    required this.title,
    required this.detail,
    required this.enabled,
    required this.action,
    this.critical = false,
  });
  final String number, title, detail;
  final bool enabled, critical;
  final Future<void> Function() action;
  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(bottom: 12),
    padding: const EdgeInsets.all(18),
    decoration: BoxDecoration(
      color: const Color(0xFF121616),
      borderRadius: BorderRadius.circular(18),
      border: Border.all(
        color: enabled
            ? const Color(0xFF50E3C2).withValues(alpha: .35)
            : Colors.white10,
      ),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          number,
          style: TextStyle(
            color: enabled ? const Color(0xFF50E3C2) : Colors.white30,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      title,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 16,
                      ),
                    ),
                  ),
                  if (critical)
                    const Text(
                      'CRITICAL',
                      style: TextStyle(
                        color: Color(0xFFF97316),
                        fontSize: 10,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                detail,
                style: const TextStyle(
                  color: Colors.white54,
                  height: 1.35,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 10),
        IconButton(
          onPressed: enabled ? null : action,
          icon: Icon(
            enabled ? Icons.check_circle : Icons.arrow_forward,
            color: enabled ? const Color(0xFF50E3C2) : Colors.white,
          ),
        ),
      ],
    ),
  );
}

class _GroqCard extends StatelessWidget {
  const _GroqCard({
    required this.controller,
    required this.saved,
    required this.busy,
    required this.message,
    required this.onTest,
  });
  final TextEditingController controller;
  final bool saved, busy;
  final String? message;
  final Future<void> Function() onTest;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(20),
    decoration: BoxDecoration(
      color: const Color(0xFF121616),
      borderRadius: BorderRadius.circular(18),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                'Groq intelligence',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
              ),
            ),
            if (saved)
              const Icon(Icons.lock, size: 18, color: Color(0xFF50E3C2)),
          ],
        ),
        const SizedBox(height: 7),
        Text(
          saved
              ? 'A key is encrypted on this device. Enter a new one only to replace it.'
              : 'Your key is encrypted on this device and is never built into the APK.',
          style: const TextStyle(
            color: Colors.white54,
            height: 1.35,
            fontSize: 13,
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          controller: controller,
          obscureText: true,
          autocorrect: false,
          decoration: InputDecoration(
            hintText: saved ? '•••••••••••• (saved)' : 'gsk_…',
            suffixIcon: IconButton(
              onPressed: busy ? null : onTest,
              icon: busy
                  ? const Padding(
                      padding: EdgeInsets.all(12),
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.wifi_tethering),
            ),
          ),
        ),
        if (message != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              message!,
              style: TextStyle(
                color: saved
                    ? const Color(0xFF50E3C2)
                    : const Color(0xFFF97316),
                fontSize: 13,
                height: 1.35,
              ),
            ),
          ),
      ],
    ),
  );
}
