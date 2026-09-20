# TPS450M app access and shop-context test

Read-only live testing on 192.168.1.64:41923, September 13.

- Soko Terminal opens and renders orders, stock alerts and profile **Osa Gadgets, Owner**.
- WhatsApp is installed and opens to its registration welcome screen. It cannot yet be used for manager alerts or customer replies.
- TikTok opens and renders the feed and editable profile **@soko24.co**. Viewing this account is not proof of authority to publish Osa Gadgets content there.
- Generic package launch probes initially failed; explicit resolved activities succeeded. Initial XML snapshots labelled whatsapp/tiktok still showed Terminal and are not proof of those apps opening. Use whatsapp-explicit.xml, tiktok-explicit.xml and tiktok-profile.xml.
- No messages, comments, posts, sales or listing edits were sent as tests. No account login or linking was changed.

## Multi-shop finding

Amara is not yet automatically bound to the seller logged into Terminal. SokoTerminalBridge only exposes protocol metadata. SecureConfig defaults businessName to Sanaa Media. Backend registration sends that value, and AgentController.sokoData selects the shop using the registered business_name, with another Sanaa Media fallback. The five most recently updated backend device records all show Sanaa Media and no soko_account_id. This read-only query does not prove the specific device mapping, but the code path does establish that Terminal login alone does not select Amara's shop.

A shared core is appropriate, but production requires authenticated immutable seller/shop ID binding, device-specific manager/channel configuration, shop-scoped memory and queue namespaces, and account-change invalidation. A display name must not serve as proof of business ownership. The current name-based/default context is insufficient for multi-shop certification.

## Outstanding setup and verification

Complete WhatsApp registration through the owner-visible login flow; specify the intended manager recipient and authorize a controlled alert test. Confirm whether @soko24.co is the approved TikTok account for Osa Gadgets. Implement verified seller-account binding before enabling cross-shop commercial automation. Local readiness previously passed; reliable end-to-end alerts and shop-specific commercial decisions are not established.

Private UI evidence: artifacts/tps-app-access-20260913/. No credentials or tokens were read or copied between devices. Amara was brought back to foreground after testing.
