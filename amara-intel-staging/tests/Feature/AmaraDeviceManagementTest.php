<?php

namespace Tests\Feature;

use App\Http\Controllers\Api\AgentController;
use App\Models\AgentConfig;
use App\Models\AgentDevice;
use App\Models\AgentMemorySnapshot;
use App\Models\User;
use App\Services\AgentDeviceManagement;
use App\Services\AgentMemoryArchive;
use App\Services\VerifiedTerminalIdentity;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Symfony\Component\HttpKernel\Exception\HttpException;
use Tests\TestCase;

class AmaraDeviceManagementTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();
        config(['database.default' => 'sqlite', 'database.connections.sqlite.database' => ':memory:', 'cache.default' => 'array', 'cache.limiter' => 'array', 'app.key' => 'base64:'.base64_encode(str_repeat('a', 32))]);
        $this->withServerVariables(['REMOTE_ADDR' => '2001:db8::'.bin2hex(random_bytes(8))]);
        DB::purge('sqlite');
        foreach (['2026_08_20_053000_create_agent_tables.php', '2026_09_03_000000_create_agent_memory_snapshots.php', '2026_08_30_000000_create_agent_heartbeats.php', '2026_09_13_120000_add_agent_device_management.php', '2026_09_13_130000_add_agent_device_pairing.php','2026_09_13_140000_add_observed_terminal_shop.php', '2026_09_24_000001_add_observed_fields_to_agent_heartbeats.php', '2026_09_24_000002_add_agent_shop_bindings.php', '2026_09_24_000003_create_agent_events.php', '2026_09_24_000004_add_agent_event_clock.php', '2026_09_25_000001_add_amara_remote_intelligence.php', '2026_09_25_000002_add_remote_lockout_reason.php', '2026_09_27_000001_add_heartbeat_system_lockout.php'] as $file) {
            (require database_path('migrations/'.$file))->up();
        }
    }

    public function test_remote_command_ack_requires_matching_revision_and_both_control_states(): void
    {
        $device = $this->device('remote');
        $command = app(\App\Services\AmaraRemoteCommands::class)
            ->issue($device, 'system_lockout', 1, 'Owner requested hold');
        $controller = app(AgentController::class);
        foreach ([
            ['remote_command_revision' => $command['revision']],
            ['remote_command_revision' => $command['revision'], 'remote_work_paused' => false, 'system_lockout' => false],
            ['remote_command_revision' => $command['revision'] + 1, 'remote_work_paused' => false, 'system_lockout' => true],
        ] as $report) {
            $controller->heartbeat($this->request('remote', $report, 'token-remote'));
            $this->assertNull(DB::table('agent_remote_commands')->where('id', $command['id'])->value('acknowledged_at'));
        }
        $controller->heartbeat($this->request('remote', [
            'remote_command_revision' => $command['revision'],
            'remote_work_paused' => false, 'system_lockout' => true,
        ], 'token-remote'));
        $this->assertNotNull(DB::table('agent_remote_commands')->where('id', $command['id'])->value('acknowledged_at'));
        $this->assertTrue($device->heartbeats()->latest('id')->first()->system_lockout);
    }

    private function device(string $id): AgentDevice
    {
        return AgentDevice::create(['device_id' => $id, 'business_name' => 'Shop '.$id, 'agent_token_hash' => hash('sha256', 'token-'.$id)]);
    }

    private function request(string $id, array $extra = [], ?string $token = null): Request
    {
        $r = Request::create('/', 'POST', array_merge(['device_id' => $id], $extra));
        if ($token) {
            $r->headers->set('Authorization', 'Bearer '.$token);
        }

        return $r;
    }

    public function test_signed_terminal_switch_updates_only_the_authenticated_device(): void
    {
        $a = $this->device('a');
        $b = $this->device('b');
        $key = openssl_pkey_new(['private_key_bits' => 2048]);
        $public = openssl_pkey_get_details($key)['key'];
        app()->instance(VerifiedTerminalIdentity::class, new VerifiedTerminalIdentity($public));
        $assertion = function (int $shop) use ($key): string {
            $claims = [
                'aud' => 'amara-shop-read', 'seller_id' => 254, 'shop_id' => $shop,
                'shop_name' => 'Shop '.$shop, 'iat' => time(), 'exp' => time() + 120,
            ];
            $payload = rtrim(strtr(base64_encode(json_encode($claims)), '+/', '-_'), '=');
            openssl_sign($payload, $signature, $key, OPENSSL_ALGO_SHA256);
            return $payload.'.'.rtrim(strtr(base64_encode($signature), '+/', '-_'), '=');
        };

        $this->postJson('/api/agent/shop-observation', ['device_id' => 'a'], [
            'Authorization' => 'Bearer token-b', 'X-Terminal-Identity' => $assertion(24),
        ])->assertUnauthorized();
        $this->assertNull($a->fresh()->observed_shop_id);

        foreach ([24, 41] as $shop) {
            $this->postJson('/api/agent/shop-observation', ['device_id' => 'a'], [
                'Authorization' => 'Bearer token-a', 'X-Terminal-Identity' => $assertion($shop),
            ])->assertOk()->assertJsonPath('shop_identity.shop_id', $shop);
            $this->assertSame($shop, $a->fresh()->observed_shop_id);
        }
        $this->assertSame(2, $a->fresh()->binding_revision);
        $this->assertSame([24, 41], $a->shopBindings()->orderBy('revision')->pluck('shop_id')->all());
        $this->assertNotNull($a->shopBindings()->where('revision', 1)->first()->ended_at);
        $this->postJson('/api/agent/shop-observation', ['device_id' => 'a'], [
            'Authorization' => 'Bearer token-a', 'X-Terminal-Identity' => $assertion(41),
        ])->assertOk()->assertJsonPath('binding_revision', 2);
        $this->assertSame(2, $a->shopBindings()->count());
        $this->assertNull($b->fresh()->observed_shop_id);
        $this->postJson('/api/agent/shop-observation', ['device_id' => 'a'], [
            'Authorization' => 'Bearer token-a', 'X-Terminal-Identity' => 'forged.proof',
        ])->assertForbidden();
        $this->assertSame(41, $a->fresh()->observed_shop_id);
    }

    public function test_memory_versions_stay_encrypted_and_separate(): void
    {
        $a = $this->device('a');
        $b = $this->device('b');
        $archive = new AgentMemoryArchive;
        $archive->save($a, 1, ['customer' => 'alpha']);
        $archive->save($a, 1, ['customer' => 'beta']);
        $archive->save($a, 1, ['customer' => 'beta']);
        $archive->save($b, 1, ['customer' => 'other']);
        $this->assertSame(2, $a->memoryVersions()->count());
        $this->assertSame(1, $b->memoryVersions()->count());
        $this->assertSame('beta', $a->memorySnapshot->payload['customer']);
        $this->assertStringNotContainsString('alpha', DB::table('agent_memory_versions')->value('payload'));
    }

    public function test_binding_migration_backfills_only_the_last_known_shop(): void
    {
        $device = $this->device('legacy-shop');
        $migration = require database_path('migrations/2026_09_24_000002_add_agent_shop_bindings.php');
        $migration->down();
        DB::table('agent_devices')->where('id', $device->id)->update([
            'observed_seller_id' => 254, 'observed_shop_id' => 24,
            'observed_shop_name' => 'Free Line Stationery', 'shop_verified_at' => now()->subDay(),
        ]);
        $migration->up();
        $binding = $device->fresh()->shopBindings()->sole();
        $this->assertSame(1, $device->fresh()->binding_revision);
        $this->assertSame(24, $binding->shop_id);
        $this->assertTrue($binding->legacy_latest_only);
        $this->assertNull($binding->ended_at);
    }

    public function test_old_backup_is_archived_before_replacement(): void
    {
        $a = $this->device('a');
        AgentMemorySnapshot::create(['agent_device_id' => $a->id, 'schema_version' => 1, 'payload' => ['old' => true], 'captured_at' => now()]);
        (new AgentMemoryArchive)->save($a, 1, ['new' => true]);
        $this->assertSame(['old' => true], $a->memoryVersions()->oldest('id')->first()->payload);
    }

    public function test_configuration_change_is_device_scoped_and_keeps_secrets(): void
    {
        $a = $this->device('a');
        $b = $this->device('b');
        AgentConfig::create(['agent_device_id' => $a->id, 'config_json' => ['groq_api_key' => 'private-value']]);
        $input = array_fill_keys(AgentDeviceManagement::CHANNELS, false) + ['label' => 'TPS450M', 'manager_whatsapp' => '+256700000001', 'business_brief' => 'Prioritize stock questions'];
        $input['tiktok_test_mode'] = true;
        (new AgentDeviceManagement)->save($a, $input, 1);
        $this->assertTrue($a->fresh()->configuration->config_json['tiktok_test_mode']);
        $this->assertFalse($a->fresh()->configuration->config_json['whatsapp_automation_enabled']);
        $this->assertSame('private-value', $a->fresh()->configuration->config_json['groq_api_key']);
        $this->assertNull($b->fresh()->configuration);
        $this->assertSame(1, DB::table('agent_admin_changes')->count());
        $this->assertStringNotContainsString('private-value', DB::table('agent_admin_changes')->value('changed_fields'));
    }

    public function test_config_requires_matching_device_token(): void
    {
        $this->device('a');
        $this->device('b');
        $this->expectException(HttpException::class);
        (new AgentController)->config($this->request('a', [], 'token-b'), 'a');
    }

    public function test_reregistration_cannot_take_over_device(): void
    {
        $a = $this->device('a');
        try {
            (new AgentController)->register($this->request('a'));
            $this->fail('Expected rejection');
        } catch (HttpException $e) {
            $this->assertSame(401, $e->getStatusCode());
        }
        $this->assertSame(hash('sha256', 'token-a'), $a->fresh()->agent_token_hash);
    }

    public function test_setup_does_not_expose_existing_device_configuration(): void
    {
        $this->device('a');
        $this->expectException(HttpException::class);
        (new AgentController)->setup($this->request('a'));
    }

    public function test_memory_read_rejects_another_devices_token(): void
    {
        $this->device('a');
        $this->device('b');
        $this->expectException(HttpException::class);
        (new AgentController)->memory($this->request('a', [], 'token-b'), 'a');
    }

    public function test_admin_module_rejects_support_and_members(): void
    {
        foreach (['member', 'support'] as $role) {
            $user = new User;
            $user->role = $role;
            $this->actingAs($user);
            $this->assertFalse(\App\Filament\Pages\AmaraDevices::canAccess());
        }
        $user = new User;
        $user->role = 'admin';
        $this->actingAs($user);
        $this->assertTrue(\App\Filament\Pages\AmaraDevices::canAccess());
        $this->assertArrayHasKey('devices', \App\Filament\AppModules::admin());
    }

    public function test_admin_can_render_and_save_device_without_exposing_credentials(): void
    {
        config(['session.driver' => 'array', 'cache.default' => 'array']);
        $user = new User;
        $user->id = 1;
        $user->role = 'admin';
        $this->actingAs($user);
        $device = $this->device('render-device');
        AgentConfig::create(['agent_device_id' => $device->id, 'config_json' => ['groq_api_key' => 'private-render-secret']]);
        \Filament\Facades\Filament::setCurrentPanel(\Filament\Facades\Filament::getPanel('admin'));
        \Livewire\Livewire::test(\App\Filament\Pages\AmaraDevices::class)
            ->assertSee('render-device')->call('selectDevice', $device->id)
            ->assertDontSee('private-render-secret')->set('settings.label', 'Counter terminal')
            ->call('save')->assertHasNoErrors();
        $this->assertSame('Counter terminal', $device->fresh()->label);
        $this->assertArrayNotHasKey('tiktok_test_mode', $device->fresh()->configuration->config_json);
        $this->assertArrayNotHasKey('manager_whatsapp', $device->fresh()->configuration->config_json);
    }

    public function test_amara_sidebar_sections_and_fleet_log_filters(): void
    {
        config(['session.driver' => 'array', 'cache.default' => 'array']);
        $user = new User;
        $user->id = 1;
        $user->role = 'admin';
        $this->actingAs($user);
        \Filament\Facades\Filament::setCurrentPanel(\Filament\Facades\Filament::getPanel('admin'));
        $a = $this->device('log-device-a');
        $b = $this->device('log-device-b');
        foreach ([[$a, 'publish', true, false], [$a, 'reply', false, true], [$b, 'publish', false, false]] as [$device, $action, $success, $escalated]) {
            $device->logs()->create([
                'device_id' => $device->device_id, 'module' => 'Work', 'action' => $action,
                'summary' => 'Fixture '.$device->device_id.' '.$action,
                'success' => $success, 'escalated' => $escalated,
            ]);
        }

        $this->assertSame('Fleet overview', \App\Filament\Pages\AmaraDevices::getNavigationLabel());
        $this->assertSame('Activity logs', \App\Filament\Pages\AmaraDeviceLogs::getNavigationLabel());
        $this->assertSame('Web nodes', \App\Filament\Pages\AmaraWebNodes::getNavigationLabel());

        \Livewire\Livewire::test(\App\Filament\Pages\AmaraDeviceLogs::class)
            ->assertSee('Activity across Amara devices')
            ->assertSee('Fixture log-device-a publish')
            ->assertSee('Fixture log-device-b publish')
            ->set('logOutcome', 'review')
            ->assertSee('Fixture log-device-a reply')
            ->assertDontSee('Fixture log-device-b publish')
            ->set('logOutcome', '')
            ->set('logSearch', 'log-device-b')
            ->assertSee('Fixture log-device-b publish')
            ->assertDontSee('Fixture log-device-a publish');
        \Livewire\Livewire::test(\App\Filament\Pages\AmaraWebNodes::class)
            ->assertSee('Shop and worker nodes')->assertSee('log-device-a');
    }

    public function test_fleet_page_paginates_a_thousand_devices_and_links_shops_to_devices(): void
    {
        config(['session.driver' => 'array', 'cache.default' => 'array']);
        $user = new User;
        $user->id = 1;
        $user->role = 'admin';
        $this->actingAs($user);
        \Filament\Facades\Filament::setCurrentPanel(\Filament\Facades\Filament::getPanel('admin'));
        $rows = [];
        for ($i = 1; $i <= 1001; $i++) {
            $rows[] = [
                'device_id' => sprintf('device-%04d', $i), 'agent_name' => 'Amara',
                'business_name' => 'Fixture shop', 'agent_token_hash' => str_repeat('a', 64),
                'observed_seller_id' => $i <= 500 ? 254 : 708,
                'observed_shop_id' => $i <= 500 ? 24 : 128,
                'observed_shop_name' => $i <= 500 ? 'Free Line Stationery' : 'Sanaa Media',
                'created_at' => now(), 'updated_at' => now(),
            ];
        }
        foreach (array_chunk($rows, 200) as $chunk) {
            DB::table('agent_devices')->insert($chunk);
        }
        $page = \Livewire\Livewire::test(\App\Filament\Pages\AmaraDevices::class)
            ->assertSee('Shop → device network')->assertSee('device-1001')->assertDontSee('device-0001')
            ->assertSee('501 devices')->call('selectShop', '254:24')
            ->assertSee('Free Line Stationery')->assertDontSee('device-1001');
        $page->set('search', 'device-0001')->assertSee('device-0001')->assertDontSee('device-0002');
        $page->set('shop', '')->set('search', 'Free Line Stationery')->assertSee('device-0500')->assertDontSee('device-0501');
        $graph = \Livewire\Livewire::test(\App\Filament\Pages\AmaraWebNodes::class)
            ->instance()->getNodeGraphProperty();
        $this->assertSame(1001, $graph['totalWorkers']);
        $this->assertSame(200, $graph['shownWorkers']);
    }

    public function test_admin_can_follow_a_device_into_signed_shop_history(): void
    {
        config(['session.driver' => 'array', 'cache.default' => 'array']);
        $user = new User;
        $user->id = 1;
        $user->role = 'admin';
        $this->actingAs($user);
        \Filament\Facades\Filament::setCurrentPanel(\Filament\Facades\Filament::getPanel('admin'));
        $device = $this->device('history-device');
        $device->shopBindings()->create([
            'revision' => 1, 'seller_id' => 254, 'shop_id' => 24,
            'shop_name' => 'Free Line Stationery', 'started_at' => now()->subHour(),
            'last_verified_at' => now(), 'legacy_latest_only' => true,
        ]);
        \Livewire\Livewire::test(\App\Filament\Pages\AmaraDevices::class)
            ->call('selectDevice', $device->id)->call('showTab', 'shops')
            ->assertSee('Revision 1')->assertSee('Free Line Stationery')
            ->assertSee('legacy latest-only backfill');
    }

    public function test_admin_can_follow_work_evidence_for_one_device_and_job(): void
    {
        config(['session.driver' => 'array', 'cache.default' => 'array']);
        $user = new User;
        $user->id = 1;
        $user->role = 'admin';
        $this->actingAs($user);
        \Filament\Facades\Filament::setCurrentPanel(\Filament\Facades\Filament::getPanel('admin'));
        $a = $this->device('event-a');
        $b = $this->device('event-b');
        $job = (string) \Illuminate\Support\Str::uuid();
        foreach ([[$a, 'work.outcome', $job], [$a, 'health.changed', null], [$b, 'effect.verified', $job]] as [$device, $type, $jobId]) {
            $device->events()->create([
                'installation_id' => (string) \Illuminate\Support\Str::uuid(), 'event_id' => (string) \Illuminate\Support\Str::uuid(),
                'sequence' => 1, 'binding_revision' => 1, 'seller_id' => 254, 'shop_id' => 24,
                'event_type' => $type, 'job_id' => $jobId, 'app_build' => 65,
                'occurred_at' => now(), 'received_at' => now(), 'facts' => [],
                'canonical_digest' => str_repeat('a', 64),
            ]);
        }
        \Livewire\Livewire::test(\App\Filament\Pages\AmaraDevices::class)
            ->call('selectDevice', $a->id)->call('showTab', 'events')
            ->assertSee('work.outcome')->assertSee('health.changed')->assertDontSee('effect.verified')
            ->call('selectJob', $job)->assertSee('work.outcome')->assertDontSee('health.changed');
    }

    public function test_fleet_status_distinguishes_unobserved_control_from_unbound_control(): void
    {
        $device = $this->device('status-device');
        $heartbeat = $device->heartbeats()->create([
            'device_id' => $device->device_id, 'reported_at' => now(),
            'accessibility_bound' => false, 'observed_fields' => [],
        ]);
        $this->assertSame('online', \App\Services\AmaraFleetStatus::state($heartbeat));
        $this->assertNull(\App\Services\AmaraFleetStatus::observed($heartbeat, 'accessibility_bound'));
        $heartbeat->observed_fields = ['accessibility_bound'];
        $this->assertSame('degraded', \App\Services\AmaraFleetStatus::state($heartbeat));
        $heartbeat->reported_at = now()->subMinutes(16);
        $this->assertSame('stale', \App\Services\AmaraFleetStatus::state($heartbeat));
        $this->assertSame('unknown', \App\Services\AmaraFleetStatus::state(null));
    }

    public function test_non_admin_cannot_read_fleet_graph_data(): void
    {
        $user = new User;
        $user->role = 'member';
        $this->actingAs($user);
        $this->expectException(HttpException::class);
        (new \App\Filament\Pages\AmaraWebNodes)->getNodeGraphProperty();
    }

    public function test_device_reporting_filter_uses_latest_heartbeat_and_quick_links(): void
    {
        config(['session.driver' => 'array', 'cache.default' => 'array']);
        $user = new User;
        $user->id = 1;
        $user->role = 'admin';
        $this->actingAs($user);
        \Filament\Facades\Filament::setCurrentPanel(\Filament\Facades\Filament::getPanel('admin'));
        $recent = $this->device('recent-worker');
        $stale = $this->device('stale-worker');
        $this->device('never-worker');
        foreach ([[$recent, now()], [$stale, now()->subHour()]] as [$device, $time]) {
            $device->heartbeats()->create([
                'device_id' => $device->device_id, 'reported_at' => $time,
                'observed_fields' => [],
            ]);
        }
        DB::table('agent_devices')->where('id', $recent->id)->update([
            'observed_seller_id' => 254, 'observed_shop_id' => 24,
            'observed_shop_name' => 'Free Line Stationery',
        ]);

        \Livewire\Livewire::test(\App\Filament\Pages\AmaraDevices::class)
            ->assertSee('Registered workers')->assertSee('Never reported')
            ->set('reporting', 'recent')->assertSee('recent-worker')->assertDontSee('stale-worker')
            ->set('reporting', 'stale')->assertSee('stale-worker')->assertDontSee('recent-worker')
            ->set('reporting', 'never')->assertSee('never-worker')->assertDontSee('recent-worker')
            ->call('openDeviceTab', $recent->id, 'events')
            ->assertSet('detailTab', 'events')->assertSee('Work evidence');
        $nodesPage = \Livewire\Livewire::test(\App\Filament\Pages\AmaraWebNodes::class)
            ->assertSee('Amara fleet graph')->assertSee('Graph')->assertSee('List')
            ->assertSee('recent-worker');
        $graph = $nodesPage->instance()->getNodeGraphProperty();
        $this->assertSame(3, $graph['shownWorkers']);
        $this->assertContains('shop:254:24', array_column($graph['nodes'], 'id'));
        $this->assertContains('shop:unverified', array_column($graph['nodes'], 'id'));
        $this->assertContains('observed_shop', array_column($graph['edges'], 'kind'));
        $this->assertContains('unverified', array_column($graph['edges'], 'kind'));
        $this->assertStringNotContainsString('agent_token_hash', json_encode($graph));
    }

    public function test_pairing_is_device_bound_single_use_and_keeps_memories(): void
    {
        $a = $this->device('a');
        $b = $this->device('b');
        $service = new \App\Services\AgentDevicePairing;
        (new AgentMemoryArchive)->save($a, 1, ['keep' => 'memory']);
        $code = $service->issue($a, 1);
        $this->assertSame(hash('sha256', 'token-a'), $a->fresh()->agent_token_hash);
        try {
            $service->redeem('b', $code);
            $this->fail('Cross-device pairing accepted');
        } catch (HttpException $e) {
            $this->assertSame(401, $e->getStatusCode());
        }
        $token = $service->redeem('a', $code);
        $this->assertSame(hash('sha256', $token), $a->fresh()->agent_token_hash);
        $this->assertSame(['keep' => 'memory'], $a->fresh()->memorySnapshot->payload);
        $this->expectException(HttpException::class);
        $service->redeem('a', $code);
    }

    public function test_expired_pairing_does_not_rotate_credential(): void
    {
        $a = $this->device('a');
        $service = new \App\Services\AgentDevicePairing;
        $code = $service->issue($a, 1);
        $a->forceFill(['pairing_expires_at' => now()->subSecond()])->save();
        try {
            $service->redeem('a', $code);
            $this->fail('Expired code accepted');
        } catch (HttpException $e) {
            $this->assertSame(401, $e->getStatusCode());
        }
        $this->assertSame(hash('sha256', 'token-a'), $a->fresh()->agent_token_hash);
    }
}
