import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/commercial/commercial_screen.dart';

void main() {
  test('timezone edit preserves policy fields outside the editor', () {
    final original = <String, dynamic>{
      'dailyQualifiedInquiryTarget': 2,
      'weeklyVerifiedSaleTarget': 3,
      'monthlyProfitFloorUgx': 0,
      'ownerTimeZoneId': '',
      'allowedProducts': ['printer'],
      'approvedChannels': ['whatsapp'],
      'permittedAudience': 'consented customers',
      'quietHoursStart': '21:00',
      'quietHoursEnd': '07:00',
      'dailyGlobalMessageCap': 4,
      'perCustomerDailyCap': 1,
      'perCustomerFrequencyWindowMs': 259200000,
      'followUpLimitPerOpportunity': 2,
      'campaignBudgetUgx': 12000,
      'discountCeilingPercent': 5,
      'attributionWindowMs': 604800000,
      'maxConcurrentExperiments': 2,
      'maxExperimentSpendUgx': 4000,
      'stopOnComplaint': false,
      'stopOnRefundSpike': true,
      'stopOnNegativeReplySpike': false,
    };
    final edited = parsePolicy(jsonEncode(original));
    edited['ownerTimeZoneId'] = 'Africa/Kampala';
    final saved = jsonDecode(encodePolicy(edited)) as Map<String, dynamic>;
    for (final key in [
      'perCustomerFrequencyWindowMs',
      'maxConcurrentExperiments',
      'maxExperimentSpendUgx',
      'stopOnComplaint',
      'stopOnRefundSpike',
      'stopOnNegativeReplySpike',
      'dailyGlobalMessageCap',
      'campaignBudgetUgx',
    ]) {
      expect(saved[key], original[key], reason: key);
    }
    expect(saved['ownerTimeZoneId'], 'Africa/Kampala');
    expect(saved['approvedChannels'], ['whatsapp']);
    expect(saved['quietHoursStart'], '21:00');
  });
}
