import 'package:flutter/material.dart';

/// Gestures open the same review actions as buttons. Nothing disappears optimistically.
class TaskSwipe extends StatelessWidget {
  const TaskSwipe({
    super.key,
    required this.id,
    required this.child,
    required this.onLeft,
    required this.onRight,
    this.leftLabel = 'Archive',
    this.rightLabel = 'Review',
  });
  final String id, leftLabel, rightLabel;
  final Widget child;
  final Future<void> Function() onLeft, onRight;

  @override
  Widget build(BuildContext context) => Dismissible(
    key: ValueKey('swipe-$id'),
    background: _background(
      rightLabel,
      Alignment.centerLeft,
      const Color(0xFF164B40),
    ),
    secondaryBackground: _background(
      leftLabel,
      Alignment.centerRight,
      const Color(0xFF56312B),
    ),
    confirmDismiss: (direction) async {
      if (direction == DismissDirection.startToEnd) {
        await onRight();
      } else {
        await onLeft();
      }
      return false;
    },
    child: child,
  );
  Widget _background(String label, Alignment alignment, Color color) =>
      Container(
        alignment: alignment,
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Text(
          label,
          style: const TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
          ),
        ),
      );
}
