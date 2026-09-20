import 'package:flutter/material.dart';
import '../screens/doctor/doctor_screen.dart';

/// Compact summary with every issue retained in an accessible detail sheet.
class AttentionCard extends StatelessWidget {
  const AttentionCard({super.key, required this.issues});
  final List<Map<String, dynamic>> issues;

  @override
  Widget build(BuildContext context) {
    if (issues.isEmpty) return const SizedBox.shrink();
    final ordered = [...issues]
      ..sort(
        (a, b) => (b['ownerAction'] == true ? 1 : 0).compareTo(
          a['ownerAction'] == true ? 1 : 0,
        ),
      );
    final first = ordered.first;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 12),
      color: const Color(0xFF211E18),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(
                  Icons.notifications_none_rounded,
                  color: Color(0xFFEAC48A),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    '${issues.length} ${issues.length == 1 ? 'issue' : 'issues'} to review',
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 16,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              '${first['task']} · ${first['ownerAction'] == true ? 'Owner action needed' : 'Waiting / recovery'}',
              style: const TextStyle(color: Color(0xFFEAC48A), fontSize: 12),
            ),
            const SizedBox(height: 6),
            Text(
              '${first['reason'] ?? 'Details unavailable'}',
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
            ),
            if ((first['action'] ?? '').toString().isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                '${first['action']}',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.white70),
              ),
            ],
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                TextButton(
                  onPressed: () => _details(context, ordered),
                  child: const Text('View all issues'),
                ),
                TextButton.icon(
                  onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const DoctorScreen()),
                  ),
                  icon: const Icon(Icons.health_and_safety_outlined, size: 18),
                  label: const Text('Check & repair'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _details(BuildContext context, List<Map<String, dynamic>> ordered) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: .7,
        minChildSize: .35,
        maxChildSize: .95,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
          children: [
            Text(
              'What needs attention',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            const Text(
              'Issues stay visible until a check confirms recovery or you resolve the affected work.',
            ),
            const SizedBox(height: 16),
            for (final issue in ordered)
              Padding(
                padding: const EdgeInsets.only(bottom: 20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${issue['task'] ?? 'Health issue'}',
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 6),
                    Text('${issue['reason'] ?? 'Reason unavailable'}'),
                    if (issue['action'] != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Text('${issue['action']}'),
                      ),
                    if (issue['continuation'] != null)
                      Text(
                        '${issue['continuation']}',
                        style: const TextStyle(color: Colors.white60),
                      ),
                  ],
                ),
              ),
            FilledButton(
              onPressed: () {
                Navigator.pop(context);
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const DoctorScreen()),
                );
              },
              child: const Text('Open Doctor'),
            ),
          ],
        ),
      ),
    );
  }
}
