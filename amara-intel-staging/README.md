# Cards backend staging files

These files extend the Cards Laravel/Filament application. This directory is not
a standalone backend: it has no Composer project, routes, bootstrap, or complete
database schema. Apply and test it in a compatible Cards checkout; committing it
does not deploy it.

Dependencies include the existing agent models, pairing, management, memory,
fleet status, terminal identity and Soko services, navigation trait, API routes,
and the earlier migrations listed in `AmaraDeviceManagementTest::setUp`.

Run all three migrations included here, including
`2026_09_27_000001_add_heartbeat_system_lockout.php`. The heartbeat controller
always includes that nullable column in inserts.

The 2026-09-27 audit tested these files over an isolated copy of the local Cards
application with SQLite in memory: 21 feature tests and 117 assertions passed.
This result depends on that host application's code and installed dependencies;
it is not a clean-checkout test of this directory alone.

Remote commands require an admin at the Filament boundary. Acknowledgement
requires the current revision and both matching control states. The fleet UI's
lockout display still represents desired configuration, and device-wide lockout
enforcement remains incomplete; see `../docs/audits/2026-09-27-repository-audit.md`.
