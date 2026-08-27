import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';
import '../contacts/contact_permissions_screen.dart';
import '../permissions/phone_access_screen.dart';

class ToolsTab extends StatefulWidget {
  const ToolsTab({super.key});

  @override
  State<ToolsTab> createState() => _ToolsTabState();
}

class _ToolsTabState extends State<ToolsTab> {
  List<Map<String, dynamic>> _diagnoses = [];
  bool _loading = true;
  Map<String, dynamic> _health = const {};

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    try {
      final diagnoses = await AgentChannel.selfHealingDiagnose();
      final health = await AgentChannel.capabilityHealth();
      if (mounted) {
        setState(() {
          _diagnoses = diagnoses;
          _health = health;
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
      title: const Text('Tools', style: TextStyle(fontWeight: FontWeight.w800)),
      actions: [
        IconButton(
          onPressed: _refresh,
          icon: const Icon(Icons.refresh, color: Colors.white38),
        ),
      ],
    ),
    body: _loading
        ? const Center(child: CircularProgressIndicator())
        : RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(18, 8, 18, 40),
              children: [
                _sectionHeader('PERMISSIONS', _diagnoses.length),
                ..._diagnoses.map((d) => _permissionTile(d)),
                const SizedBox(height: 24),
                _sectionHeader('SECURE ACCESS', 0),
                _navTile(
                  Icons.admin_panel_settings_outlined,
                  'Phone access & credentials',
                  'Repair permissions and securely remember the Soko PIN',
                  () {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const PhoneAccessScreen(),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 24),
                _sectionHeader('CONTACTS', 0),
                _navTile(
                  Icons.people_outline,
                  'Contact permissions',
                  'Choose who Amara can message',
                  () {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const ContactPermissionsScreen(),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 24),
                _sectionHeader('CAPABILITIES', _health.length),
                ..._health.entries.map((e) => _healthTile(e.key, e.value)),
              ],
            ),
          ),
  );

  Widget _sectionHeader(String title, int count) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Text(
      '$title${count > 0 ? '  $count' : ''}',
      style: const TextStyle(
        color: Colors.white38,
        letterSpacing: 1.5,
        fontWeight: FontWeight.w800,
        fontSize: 11,
      ),
    ),
  );

  Widget _permissionTile(Map<String, dynamic> d) {
    final status = d['status']?.toString() ?? 'BLOCKED';
    final ready = status == 'READY';
    final needsAction = status == 'NEEDS_ACTION';
    final color = ready ? const Color(0xFF50E3C2) : const Color(0xFFF97316);
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(shape: BoxShape.circle, color: color),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    d['label']?.toString() ?? '',
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    d['description']?.toString() ?? '',
                    style: const TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                ],
              ),
            ),
            if (needsAction)
              FilledButton(
                onPressed: () =>
                    AgentChannel.selfHealingFix(d['key']?.toString() ?? ''),
                style: FilledButton.styleFrom(
                  backgroundColor: color,
                  foregroundColor: Colors.black,
                  minimumSize: const Size(0, 32),
                  textStyle: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                child: const Text('Fix'),
              )
            else
              const Icon(
                Icons.check_circle,
                color: Color(0xFF50E3C2),
                size: 18,
              ),
          ],
        ),
      ),
    );
  }

  Widget _navTile(
    IconData icon,
    String title,
    String subtitle,
    VoidCallback onTap,
  ) => Card(
    margin: const EdgeInsets.only(bottom: 6),
    child: InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Icon(icon, color: const Color(0xFF50E3C2), size: 20),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: const TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: Colors.white38),
          ],
        ),
      ),
    ),
  );

  Widget _healthTile(String key, Object? value) {
    final ready = value == true;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(
            ready ? Icons.check_circle : Icons.error_outline,
            color: ready ? const Color(0xFF50E3C2) : const Color(0xFFF97316),
            size: 16,
          ),
          const SizedBox(width: 10),
          Text(
            _formatKey(key),
            style: const TextStyle(fontSize: 13, color: Colors.white70),
          ),
        ],
      ),
    );
  }

  String _formatKey(String key) => key
      .replaceAllMapped(RegExp(r'([A-Z])'), (m) => ' ${m[0]}')
      .replaceRange(0, 1, key[0].toUpperCase())
      .trim();
}
