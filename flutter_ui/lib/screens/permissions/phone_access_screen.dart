import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';
import '../contacts/contact_permissions_screen.dart';

class PhoneAccessScreen extends StatefulWidget {
  const PhoneAccessScreen({super.key});

  @override
  State<PhoneAccessScreen> createState() => _PhoneAccessScreenState();
}

class _PhoneAccessScreenState extends State<PhoneAccessScreen>
    with WidgetsBindingObserver {
  Map<String, bool> _health = const {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh();
    }
  }

  Future<void> _refresh() async {
    final health = await AgentChannel.capabilityHealth();
    if (mounted) {
      setState(() {
        _health = health;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Phone access'),
      actions: [
        IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
      ],
    ),
    body: _loading
        ? const Center(child: CircularProgressIndicator())
        : ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 40),
            children: [
              const Text(
                'Amara checks the permission, the live connection, and the tools she needs separately. If something breaks, this page tells you what is actually wrong.',
                style: TextStyle(color: Colors.white60, height: 1.5),
              ),
              const SizedBox(height: 18),
              _item(
                'Accessibility permission',
                'accessibility',
                'Allows Amara to read and operate phone screens.',
                AgentChannel.openAccessibility,
              ),
              _item(
                'Accessibility service bound',
                'accessibilityBound',
                'Android has connected Amara’s live phone-control service.',
                AgentChannel.openAccessibility,
              ),
              _item(
                'Unrestricted battery use',
                'battery',
                'Keeps scheduled and monitoring work alive on ColorOS.',
                AgentChannel.openBattery,
              ),
              _item(
                'Notification access',
                'notificationAccess',
                'Lets Amara notice incoming customer messages.',
                AgentChannel.openNotificationAccess,
              ),
              _item(
                'Notification permission',
                'notifications',
                'Lets Amara report completed work and blockers.',
                AgentChannel.requestNotifications,
              ),
              _item(
                'Display over other apps',
                'overlay',
                'Supports visible recovery and owner prompts while another app is open.',
                AgentChannel.openOverlay,
              ),
              _item(
                'Groq intelligence',
                'groqConfigured',
                'Required for analysis and natural replies; the key is stored securely.',
                null,
              ),
              _item(
                'Soko Seller Terminal',
                'sokoTerminalInstalled',
                'Installed app Amara uses for shop operations.',
                null,
              ),
              _item(
                'Soko Buyer app',
                'sokoBuyerInstalled',
                'Installed app Amara uses to inspect the buyer experience.',
                null,
              ),
              _item(
                'Terminal PIN remembered',
                'sokoPinStored',
                'Stored encrypted for future staff unlocks and never shown here.',
                _storeSokoPin,
                allowWhenReady: true,
              ),
              _item(
                'Contacts access',
                'contactsAccess',
                'Allows unified discovery from the phone address book and WhatsApp. Discovery never grants send permission.',
                AgentChannel.requestContactsPermission,
              ),
              _item(
                'Accessibility screenshots',
                'screenshotSupported',
                'Enables private visual evidence on Android 11 or newer; older Android versions require an owner-approved capture fallback.',
                null,
              ),
              const SizedBox(height: 18),
              Material(
                color: const Color(0xFF121616),
                borderRadius: BorderRadius.circular(18),
                child: InkWell(
                  borderRadius: BorderRadius.circular(18),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => const ContactPermissionsScreen(),
                    ),
                  ),
                  child: const Padding(
                    padding: EdgeInsets.all(18),
                    child: Row(
                      children: [
                        Icon(Icons.people_outline, color: Color(0xFF50E3C2)),
                        SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Contact permissions',
                                style: TextStyle(fontWeight: FontWeight.w800),
                              ),
                              SizedBox(height: 3),
                              Text(
                                'Choose which contacts and groups Amara can message',
                                style: TextStyle(
                                  color: Colors.white54,
                                  fontSize: 13,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Icon(Icons.chevron_right, color: Colors.white38),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
  );

  Widget _item(
    String title,
    String key,
    String detail,
    Future<void> Function()? repair, {
    bool allowWhenReady = false,
  }) {
    final ready = _health[key] == true;
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              ready ? Icons.check_circle : Icons.error_outline,
              color: ready ? const Color(0xFF50E3C2) : const Color(0xFFF97316),
            ),
            const SizedBox(width: 13),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(fontWeight: FontWeight.w800),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    ready ? 'Ready · $detail' : 'Needs attention · $detail',
                    style: const TextStyle(color: Colors.white54, height: 1.35),
                  ),
                ],
              ),
            ),
            if (repair != null && (!ready || allowWhenReady))
              TextButton(
                onPressed: repair,
                child: Text(ready ? 'Update' : 'Fix'),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _storeSokoPin() async {
    final controller = TextEditingController();
    try {
      final submitted = await showDialog<String>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          title: const Text('Remember Terminal PIN'),
          content: TextField(
            controller: controller,
            autofocus: true,
            obscureText: true,
            enableSuggestions: false,
            autocorrect: false,
            keyboardType: TextInputType.number,
            maxLength: 12,
            decoration: const InputDecoration(
              labelText: 'Soko staff PIN',
              helperText:
                  'Encrypted on this phone; never sent to Amara’s brain.',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () {
                final value = controller.text;
                if (RegExp(r'^\d{4,12}$').hasMatch(value)) {
                  Navigator.pop(dialogContext, value);
                }
              },
              child: const Text('Save securely'),
            ),
          ],
        ),
      );
      if (submitted == null) return;
      final saved = await AgentChannel.storeSokoPin(submitted);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            saved
                ? 'Terminal PIN encrypted and remembered.'
                : 'Terminal PIN was not saved. Please retry.',
          ),
        ),
      );
      await _refresh();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Terminal PIN was not saved. Please retry.'),
          ),
        );
      }
    } finally {
      controller.clear();
      controller.dispose();
    }
  }
}
