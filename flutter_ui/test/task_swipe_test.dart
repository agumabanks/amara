import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/widgets/task_swipe.dart';

void main() {
  testWidgets(
    'swipes offer both actions and retain the card until server refresh',
    (tester) async {
      var left = 0, right = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TaskSwipe(
              id: 'task',
              onLeft: () async {
                left++;
              },
              onRight: () async {
                right++;
              },
              child: const SizedBox(
                height: 90,
                width: double.infinity,
                child: Text('Task'),
              ),
            ),
          ),
        ),
      );
      await tester.drag(find.text('Task'), const Offset(650, 0));
      await tester.pumpAndSettle();
      expect(right, 1);
      expect(find.text('Task'), findsOneWidget);
      await tester.drag(find.text('Task'), const Offset(-650, 0));
      await tester.pumpAndSettle();
      expect(left, 1);
      expect(find.text('Task'), findsOneWidget);
    },
  );
}
