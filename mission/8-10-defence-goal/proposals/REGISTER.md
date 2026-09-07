# Proposals and implementation traceability

| Proposal | Product benefit | Current implementation/evidence | Next task |
|---|---|---|---|
| P1 Customer-to-order journey | Customer reaches a useful next step | HumanConversationEngine/ChatStore and Soko workflows exist; complete sales chain unproven | WA-02, SO-02 |
| P2 Correct identities | Owner trusts recipient and memory | Origin routing/queue isolation deployed; follow-up identity/draft protections tested, live follow-up still unproven | WA-01, FU-01 |
| P3 Group operating profiles | Relevant participation, fewer unwelcome ads | WhatsAppGroupSettings has basic permissions/cadence; purpose/category/rules profile is a proposal | GR-02 |
| P4 Destination recovery | Stop wasting time and explain how to resume | v5 AccessibilityActions photo blockers; WhatsAppGroupSettings pause/reason; WorkExecutor due check; Settings UI resume deployed | GR-01 |
| P5 TikTok editorial identity | Give people a reason to follow | TikTokProductContent/TikTokSocialCycle exist; repeatable audience-tested series not accepted | TT-01 |
| P6 Complete Soko service listings | Make buying easier | SokoCatalogModule/SokoIntelligenceModule exist; verified service-listing improvements not evidenced in baseline | SO-01 |
| P7 Actionable market learning | Research changes a business decision | Market/knowledge/growth stores exist; positive business effect not measured | LE-01 |
| P8 Business brief and daily report | Fewer owner decisions and clearer value | BusinessOperatingBrief, commercial telemetry and dashboard exist; configured policy and owner usefulness unproven | BUS-01, RP-01 |
| P9 Honest acceptance dashboard | Prevent inflated readiness claims | This mission pack, JSON registers and local generated dashboard | DOC-01 complete; maintain each review |

Implementation references are locations for inspection, not assertions that all product behaviour is complete:

- `app/src/main/kotlin/co/sanaa/agent/modules/`
- `app/src/main/kotlin/co/sanaa/agent/core/work/`
- `app/src/main/kotlin/co/sanaa/agent/core/market/`
- `app/src/main/kotlin/co/sanaa/agent/core/knowledge/`
- `flutter_ui/lib/screens/settings/whatsapp_groups_screen.dart`

For each new proposal, record hypothesis, target audience, expected benefit, owner authority needed, bounded implementation, measurement and stop/revert condition. Do not change commercial promises merely because a competitor lists a different price.
