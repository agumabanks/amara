import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/widgets/attention_card.dart';

void main() {
  testWidgets('small screen keeps all issues accessible without flooding Home', (tester) async {
    await tester.binding.setSurfaceSize(const Size(320, 700));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final issues = List.generate(12, (i) => <String, dynamic>{'task': 'Task $i',
      'reason': 'Issue $i', 'ownerAction': i == 11, 'action': 'Review this condition in Doctor.'});
    await tester.pumpWidget(MaterialApp(home: MediaQuery(data: const MediaQueryData(
      size: Size(320, 700), textScaler: TextScaler.linear(1.4)),
      child: Scaffold(body: SingleChildScrollView(child: AttentionCard(issues: issues))))));
    expect(find.text('Issue 11'), findsOneWidget);
    expect(find.text('Issue 0'), findsNothing);
    await tester.tap(find.text('View all issues'));
    await tester.pumpAndSettle();
    expect(find.text('What needs attention'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Issue 9'), 250, scrollable: find.byType(Scrollable).last);
    expect(find.text('Issue 9'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
