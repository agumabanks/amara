<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\AgentConfig;
use App\Models\AgentDevice;
use App\Models\AgentLog;
use App\Models\AgentMemorySnapshot;
use App\Services\SokoReadRepository;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;

class AgentController extends Controller
{
    public function pair(Request $request): JsonResponse
    {
        $data = $request->validate(['device_id'=>'required|string|max:191','code'=>'required|string|size:24']);
        $token = app(\App\Services\AgentDevicePairing::class)->redeem($data['device_id'],$data['code']);
        return response()->json(['agent_token'=>$token])->header('Cache-Control','no-store');
    }

    public function register(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string', 'max:191'],
            'agent_name' => ['nullable', 'string', 'max:80'],
            'business_name' => ['nullable', 'string', 'max:120'],
            'owner_phone' => ['nullable', 'string', 'max:30'],
            'soko_account_id' => ['nullable', 'string', 'max:191'],
        ]);

        if (AgentDevice::where('device_id', $data['device_id'])->exists()) {
            $this->authenticate($request, $data['device_id']);
        }
        $plainToken = Str::random(64);
        $device = AgentDevice::firstOrCreate(
            ['device_id' => $data['device_id']],
            [
                'agent_name' => $data['agent_name'] ?? 'Amara',
                'business_name' => $data['business_name'] ?? 'Sanaa Media',
                'owner_phone' => $data['owner_phone'] ?? null,
                'soko_account_id' => $data['soko_account_id'] ?? null,
                'agent_token_hash' => hash('sha256', $plainToken),
            ],
        );

        if (! $device->wasRecentlyCreated) {
            return response()->json(['message' => 'Device already registered; retain the existing credential'], 409);
        }

        AgentConfig::firstOrCreate(
            ['agent_device_id' => $device->id],
            ['config_json' => [], 'updated_at' => now()],
        );

        return response()->json([
            'agent_token' => $plainToken,
            'device_id' => $device->device_id,
            'registered_at' => now()->toIso8601String(),
        ], $device->wasRecentlyCreated ? 201 : 200);
    }

    public function setup(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string', 'max:191'],
            'agent_name' => ['nullable', 'string', 'max:80'],
            'business_name' => ['nullable', 'string', 'max:120'],
            'owner_phone' => ['nullable', 'string', 'max:30'],
        ]);

        // Auto-register if not exists
        $device = AgentDevice::where('device_id', $data['device_id'])->first();
        $plainToken = null;

        if (!$device) {
            $plainToken = Str::random(64);
            $device = AgentDevice::create([
                'device_id' => $data['device_id'],
                'agent_name' => $data['agent_name'] ?? 'Amara',
                'business_name' => $data['business_name'] ?? 'Sanaa Media',
                'owner_phone' => $data['owner_phone'] ?? null,
                'agent_token_hash' => hash('sha256', $plainToken),
            ]);
            AgentConfig::firstOrCreate(
                ['agent_device_id' => $device->id],
                ['config_json' => [], 'updated_at' => now()],
            );
        }

        if (! $plainToken) $this->authenticate($request, $device->device_id);

        // First enrolment returns its credential; existing devices must authenticate.
        return response()->json([
            'agent_token' => $plainToken,
            'device_id' => $device->device_id,
            'registered_at' => now()->toIso8601String(),
            'config' => [
                'groq_api_key' => config('services.sanaa_agent.groq_api_key'),
                'groq_api_key_2' => config('services.sanaa_agent.groq_api_key_2'),
            'groq_api_keys' => config('services.sanaa_agent.groq_api_keys'),
                'groq_model' => config('services.sanaa_agent.groq_model'),
                'groq_endpoint' => config('services.sanaa_agent.groq_endpoint'),
                'agent_name' => $device->agent_name,
                'business_name' => $device->business_name,
                'owner_phone' => $device->owner_phone,
                'broadcast_time' => '07:00',
                'order_threshold_ugx' => 500000,
                'whatsapp_groups' => [],
                'soko_data_source' => 'database_readonly',
            ],
        ], $plainToken ? 201 : 200);
    }

    public function config(Request $request, string $deviceId): JsonResponse
    {
        $device = $this->authenticate($request, $deviceId);
        $device->forceFill(['config_fetched_at' => now()])->save();
        $overrides = $device->configuration?->config_json ?? [];

        return response()->json(array_merge([
            'groq_api_key' => config('services.sanaa_agent.groq_api_key'),
            'groq_api_key_2' => config('services.sanaa_agent.groq_api_key_2'),
            'groq_api_keys' => config('services.sanaa_agent.groq_api_keys'),
            'groq_model' => config('services.sanaa_agent.groq_model'),
            'groq_endpoint' => config('services.sanaa_agent.groq_endpoint'),
            'agent_name' => $device->agent_name,
            'business_name' => $device->business_name,
            'owner_phone' => $device->owner_phone ?? '',
            'broadcast_time' => '07:00',
            'order_threshold_ugx' => 500000,
            'whatsapp_groups' => [],
            'soko_data_source' => 'database_readonly',
            'fcm_token' => null,
        ], $overrides));
    }

    public function log(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string'],
            'module' => ['required', 'string', 'max:80'],
            'action' => ['required', 'string', 'max:80'],
            'platform' => ['nullable', 'string', 'max:40'],
            'summary' => ['required', 'string', 'max:4000'],
            'escalated' => ['sometimes', 'boolean'],
            'success' => ['required', 'boolean'],
            'error_message' => ['nullable', 'string', 'max:8000'],
            'metadata' => ['nullable', 'array'],
        ]);
        $device = $this->authenticate($request, $data['device_id']);
        $log = AgentLog::create(array_merge($data, ['agent_device_id' => $device->id]));

        return response()->json(['id' => $log->id, 'logged_at' => $log->created_at->toIso8601String()], 201);
    }

    public function escalate(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string'],
            'agent_name' => ['required', 'string'],
            'message' => ['required', 'string', 'max:4000'],
            'context' => ['nullable', 'string', 'max:8000'],
            'urgency' => ['required', 'in:low,medium,high,urgent'],
            'suggested_replies' => ['required', 'array', 'size:2'],
            'suggested_replies.*' => ['string', 'max:1000'],
        ]);
        $device = $this->authenticate($request, $data['device_id']);
        $fcmToken = $device->configuration?->config_json['fcm_token'] ?? null;
        $delivery = $this->sendFcm($fcmToken, $data);

        AgentLog::create([
            'agent_device_id' => $device->id,
            'device_id' => $device->device_id,
            'module' => 'escalation',
            'action' => 'notify_owner',
            'platform' => 'fcm',
            'summary' => $data['message'],
            'escalated' => true,
            'success' => $delivery['sent'],
            'error_message' => $delivery['error'],
            'metadata' => ['urgency' => $data['urgency'], 'suggested_replies' => $data['suggested_replies']],
        ]);

        return response()->json($delivery, $delivery['sent'] ? 202 : 503);
    }

    public function status(Request $request, string $deviceId): JsonResponse
    {
        $device = $this->authenticate($request, $deviceId);
        $logs = $device->logs()->where('created_at', '>=', now()->startOfDay());
        $heartbeat = $device->heartbeats()->orderByDesc('reported_at')->orderByDesc('id')->first();
        $availability = $this->availabilityFromHeartbeat($heartbeat);

        return response()->json([
            'device_id' => $deviceId,
            'active' => $availability['state'] === 'unknown' ? null : in_array($availability['state'], ['healthy', 'degraded', 'online'], true),
            'availability' => $availability,
            'today' => [
                'messages_replied' => (clone $logs)->where('action', 'reply')->where('success', true)->count(),
                'listings_improved' => (clone $logs)->where('action', 'update_listing')->where('success', true)->count(),
                'morning_broadcasts' => (clone $logs)->where('module', 'morning_broadcast')->where('success', true)->count(),
                'sales' => (clone $logs)->where('action', 'sale')->where('success', true)->count(),
                'revenue_ugx' => (int) (clone $logs)->where('action', 'sale')->get(['metadata'])
                    ->sum(fn (AgentLog $log) => (int) data_get($log->metadata, 'amount_ugx', 0)),
                'waiting_for_owner' => (clone $logs)->where('escalated', true)->where('success', false)->count(),
            ],
            'activity' => (clone $logs)->latest('created_at')->limit(20)->get(['module', 'action', 'summary', 'success', 'escalated', 'created_at']),
        ])->header('Cache-Control', 'no-store');
    }

    /** Only a signed, current Terminal assertion can change the observed shop. */
    public function observeShop(Request $request): JsonResponse
    {
        $data = $request->validate(['device_id' => ['required', 'string', 'max:191']]);
        $device = $this->authenticate($request, $data['device_id']);
        $identity = app(\App\Services\VerifiedTerminalIdentity::class)
            ->verify((string) $request->header('X-Terminal-Identity'));
        $device = DB::transaction(function () use ($device, $request, $identity) {
            $locked = AgentDevice::whereKey($device->id)->lockForUpdate()->firstOrFail();
            abort_unless($request->bearerToken() && hash_equals($locked->agent_token_hash, hash('sha256', $request->bearerToken())), 401);
            $observedAt = now();
            $revision = (int) $locked->binding_revision;
            $current = $revision ? DB::table('agent_shop_bindings')
                ->where('agent_device_id', $locked->id)->where('revision', $revision)->first() : null;
            if ($current && (int) $current->seller_id === $identity['seller_id'] && (int) $current->shop_id === $identity['shop_id']) {
                DB::table('agent_shop_bindings')->where('id', $current->id)->update([
                    'shop_name' => $identity['shop_name'], 'last_verified_at' => $observedAt,
                ]);
            } else {
                if ($current) DB::table('agent_shop_bindings')->where('id', $current->id)->update(['ended_at' => $observedAt]);
                $revision++;
                DB::table('agent_shop_bindings')->insert([
                    'agent_device_id' => $locked->id, 'revision' => $revision,
                    'seller_id' => $identity['seller_id'], 'shop_id' => $identity['shop_id'],
                    'shop_name' => $identity['shop_name'], 'started_at' => $observedAt,
                    'last_verified_at' => $observedAt, 'legacy_latest_only' => false,
                ]);
            }
            $locked->forceFill([
                'observed_seller_id' => $identity['seller_id'],
                'observed_shop_id' => $identity['shop_id'],
                'observed_shop_name' => $identity['shop_name'],
                'shop_verified_at' => $observedAt,
                'binding_revision' => $revision,
            ])->save();

            return $locked;
        });

        return response()->json([
            'device_id' => $device->device_id,
            'shop_identity' => [
                'seller_id' => $identity['seller_id'],
                'shop_id' => $identity['shop_id'],
                'shop_name' => $identity['shop_name'],
            ],
            'observed_at' => $device->shop_verified_at->toIso8601String(),
            'binding_revision' => $device->binding_revision,
        ])->header('Cache-Control', 'no-store');
    }

    public function generateAd(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string'],
            'listing' => ['sometimes', 'array'],
        ]);
        $device = $this->authenticate($request, $data['device_id']);
        $identity = app(\App\Services\VerifiedTerminalIdentity::class)->verify((string)$request->header('X-Terminal-Identity'));
        $repository = app(SokoReadRepository::class)->forIdentity($identity['seller_id'],$identity['shop_id']);
        $listing = (array) $repository->activeListings($identity['shop_name'])->first(fn($row) => (string)$row->id === (string)($data['listing']['id'] ?? ''));
        if (!isset($data['listing'])) $listing = (array)$repository->firstActiveListing($identity['shop_name']);
        if ($listing === []) {
            return response()->json(['message' => 'No active Soko listing was found'], 404);
        }
        $key = config('services.sanaa_agent.groq_api_key');
        if (! $key) {
            return response()->json(['message' => 'Groq is not configured'], 503);
        }

        $response = Http::timeout(45)->retry(2, 500)->withToken($key)->post(
            config('services.sanaa_agent.groq_endpoint'),
            [
                'model' => config('services.sanaa_agent.groq_model'),
                'max_completion_tokens' => 500,
                'response_format' => ['type' => 'json_object'],
                'messages' => [[
                    'role' => 'user',
                    'content' => 'Create a Kampala-market WhatsApp ad. Return JSON with caption and headline only. Listing: '.json_encode($listing),
                ]],
            ],
        );
        if (! $response->successful()) {
            return response()->json(['message' => 'Ad generation failed', 'upstream_status' => $response->status()], 502);
        }

        $content = data_get($response->json(), 'choices.0.message.content', '{}');
        $ad = json_decode($content, true);
        if (! is_array($ad)) {
            return response()->json(['message' => 'Groq returned malformed JSON'], 502);
        }

        return response()->json([
            'headline' => $ad['headline'] ?? '',
            'caption' => $ad['caption'] ?? '',
            'image_url' => data_get($listing, 'image_url'),
            'listing' => $listing,
        ]);
    }

    /** Store one current device snapshot. The model encrypts the JSON with APP_KEY at rest. */
    public function saveMemory(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string', 'max:191'],
            'schema_version' => ['required', 'integer', 'min:1', 'max:10'],
            'snapshot' => ['required', 'array'],
        ]);
        if (strlen(json_encode($data['snapshot'], JSON_THROW_ON_ERROR)) > 2_000_000) {
            return response()->json(['message' => 'Memory snapshot is too large'], 413);
        }
        $device = $this->authenticate($request, $data['device_id']);
        $snapshot = app(\App\Services\AgentMemoryArchive::class)->save($device, $data['schema_version'], $data['snapshot']);
        $configuration = $device->configuration;
        $remoteConfig = $configuration?->config_json ?? [];
        $remoteConfig['memory_backup_enabled'] = true;
        $remoteConfig['memory_auto_restore_enabled'] = true;
        $configuration?->update(['config_json' => $remoteConfig]);
        return response()->json([
            'saved' => true,
            'schema_version' => $snapshot->schema_version,
            'captured_at' => $snapshot->captured_at?->toIso8601String(),
        ]);
    }

    public function memory(Request $request, string $deviceId): JsonResponse
    {
        $device = $this->authenticate($request, $deviceId);
        $snapshot = AgentMemorySnapshot::where('agent_device_id', $device->id)->first();
        if (! $snapshot) return response()->json(['message' => 'No memory snapshot exists'], 404);
        return response()->json([
            'schema_version' => $snapshot->schema_version,
            'captured_at' => $snapshot->captured_at?->toIso8601String(),
            'snapshot' => $snapshot->payload,
        ]);
    }

    public function sokoData(Request $request, string $resource, SokoReadRepository $soko): JsonResponse
    {
        $deviceId = (string) $request->query('device_id');
        $device = $this->authenticate($request, $deviceId);
        $identity = app(\App\Services\VerifiedTerminalIdentity::class)->verify((string)$request->header('X-Terminal-Identity'));
        $soko = $soko->forIdentity($identity['seller_id'],$identity['shop_id']);
        $shop = $identity['shop_name'];
        $device->forceFill(['observed_seller_id'=>$identity['seller_id'],'observed_shop_id'=>$identity['shop_id'],'observed_shop_name'=>$shop,'shop_verified_at'=>now()])->save();

        $data = match ($resource) {
            'listings', 'products' => $soko->activeListings($shop),
            'orders' => $soko->recentOrders($shop),
            'messages' => $soko->buyerMessages($shop),
            'services' => $soko->publishedServices($shop),
            'commerce' => $soko->commerceProfile($shop),
            default => abort(404, 'Unknown Soko resource'),
        };

        return response()->json([
            'shop_identity' => $identity,
            'data_source' => 'soko_database_readonly',
            'resource' => $resource,
            'data' => $data->values(),
        ]);
    }

    private function authenticate(Request $request, string $deviceId): AgentDevice
    {
        $device = AgentDevice::where('device_id', $deviceId)->firstOrFail();
        $token = $request->bearerToken();
        if (! $token || ! hash_equals($device->agent_token_hash, hash('sha256', $token))) {
            abort(401, 'Invalid agent token');
        }

        return $device;
    }

    /** @return array{sent: bool, error: ?string} */
    private function sendFcm(?string $token, array $payload): array
    {
        $serverKey = config('services.sanaa_agent.fcm_server_key');
        if (! $token || ! $serverKey) {
            return ['sent' => false, 'error' => 'FCM token or server key is not configured'];
        }

        $response = Http::withHeaders(['Authorization' => 'key='.$serverKey])->post('https://fcm.googleapis.com/fcm/send', [
            'to' => $token,
            'notification' => ['title' => strtoupper($payload['urgency']).' — '.$payload['agent_name'], 'body' => $payload['message']],
            'data' => $payload,
        ]);

        return ['sent' => $response->successful(), 'error' => $response->successful() ? null : 'FCM returned HTTP '.$response->status()];
    }

    public function heartbeat(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string', 'max:191'],
            'agent_version' => ['nullable', 'string', 'max:40'],
            'uptime_ms' => ['nullable', 'integer'],
            'accessibility_bound' => ['nullable', 'boolean'],
            'groq_healthy' => ['nullable', 'boolean'],
            'pending_tasks' => ['nullable', 'integer'],
            'completed_tasks_24h' => ['nullable', 'integer'],
            'failed_tasks_24h' => ['nullable', 'integer'],
            'battery_level' => ['nullable', 'integer'],
            'is_charging' => ['nullable', 'boolean'],
            'memory_usage_mb' => ['nullable', 'integer'],
            'network_state' => ['nullable', 'in:ONLINE,LIMITED,OFFLINE'],
            'remote_command_revision' => ['nullable', 'integer', 'min:0'],
            'remote_work_paused' => ['nullable', 'boolean'],
            'system_lockout' => ['nullable', 'boolean'],
        ]);

        $device = $this->authenticate($request, $data['device_id']);

        $heartbeat = \App\Models\AgentHeartbeat::create([
            'agent_device_id' => $device->id,
            'device_id' => $data['device_id'],
            'agent_version' => $data['agent_version'] ?? null,
            'uptime_ms' => $data['uptime_ms'] ?? null,
            'accessibility_bound' => $data['accessibility_bound'] ?? false,
            'groq_healthy' => $data['groq_healthy'] ?? false,
            'pending_tasks' => $data['pending_tasks'] ?? 0,
            'completed_tasks_24h' => $data['completed_tasks_24h'] ?? 0,
            'failed_tasks_24h' => $data['failed_tasks_24h'] ?? 0,
            'battery_level' => $data['battery_level'] ?? null,
            'is_charging' => $data['is_charging'] ?? false,
            'memory_usage_mb' => $data['memory_usage_mb'] ?? null,
            'network_state' => $data['network_state'] ?? null,
            'remote_command_revision' => $data['remote_command_revision'] ?? null,
            'remote_work_paused' => $data['remote_work_paused'] ?? null,
            'system_lockout' => $data['system_lockout'] ?? null,
            'observed_fields' => array_keys(array_filter($data, fn ($value, $key) =>
                $key !== 'device_id' && $value !== null, ARRAY_FILTER_USE_BOTH)),
            'reported_at' => now(),
        ]);

        $device->touch();

        if (isset($data['remote_command_revision'])) {
            $expected = $device->configuration?->config_json ?? [];
            // A revision alone proves receipt, not that either control was applied.
            $matches = isset($data['remote_work_paused'], $data['system_lockout'])
                && (int) ($expected['remote_command_revision'] ?? -1) === (int) $data['remote_command_revision'];
            if ($matches && isset($data['remote_work_paused'])) $matches = $matches && (bool) ($expected['remote_work_paused'] ?? false) === (bool) $data['remote_work_paused'];
            if ($matches && isset($data['system_lockout'])) $matches = $matches && (bool) ($expected['system_lockout'] ?? false) === (bool) $data['system_lockout'];
            if ($matches) {
                \Illuminate\Support\Facades\DB::table('agent_remote_commands')
                    ->where('agent_device_id', $device->id)->where('revision', $data['remote_command_revision'])
                    ->whereNull('acknowledged_at')->update(['acknowledged_at' => now()]);
            }
        }

        return response()->json([
            'received' => true,
            'heartbeat_id' => $heartbeat->id,
            'server_time' => now()->toIso8601String(),
        ], 201);
    }

    public function latest(Request $request, string $deviceId): JsonResponse
    {
        $device = $this->authenticate($request, $deviceId);
        $latest = $device->heartbeats()->orderByDesc('reported_at')->orderByDesc('id')->first();
        if (! $latest) {
            return response()->json(['message' => 'No heartbeats recorded'], 404)
                ->header('Cache-Control', 'no-store');
        }

        return response()->json([
            'device_id' => $device->device_id,
            'latest_heartbeat' => [
                'received_at' => $latest->reported_at->toIso8601String(),
                'agent_version' => $latest->agent_version,
                'uptime_ms' => $latest->uptime_ms,
                'accessibility_bound' => $this->observedHeartbeatValue($latest, 'accessibility_bound'),
                'groq_healthy' => $this->observedHeartbeatValue($latest, 'groq_healthy'),
                'battery_level' => $this->observedHeartbeatValue($latest, 'battery_level'),
                'is_charging' => $this->observedHeartbeatValue($latest, 'is_charging'),
                'pending_tasks' => $this->observedHeartbeatValue($latest, 'pending_tasks'),
                'completed_tasks_24h' => $this->observedHeartbeatValue($latest, 'completed_tasks_24h'),
                'failed_tasks_24h' => $this->observedHeartbeatValue($latest, 'failed_tasks_24h'),
            ],
            'status' => $this->availabilityFromHeartbeat($latest)['state'],
            'availability' => $this->availabilityFromHeartbeat($latest),
        ])->header('Cache-Control', 'no-store');
    }

    private function availabilityFromHeartbeat(?\App\Models\AgentHeartbeat $heartbeat): array
    {
        if (! $heartbeat || ! $heartbeat->reported_at) {
            return ['state' => 'unknown', 'last_seen_at' => null, 'freshness_seconds' => null];
        }

        $age = max(0, now()->diffInSeconds($heartbeat->reported_at, false) * -1);
        $state = $age > 900 ? 'stale' :
            (! in_array('accessibility_bound', $heartbeat->observed_fields ?? [], true) ? 'online' :
                ($heartbeat->accessibility_bound ? 'healthy' : 'degraded'));

        return [
            'state' => $state,
            'last_seen_at' => $heartbeat->reported_at->toIso8601String(),
            'freshness_seconds' => (int) $age,
        ];
    }

    private function observedHeartbeatValue(\App\Models\AgentHeartbeat $heartbeat, string $field): mixed
    {
        return in_array($field, $heartbeat->observed_fields ?? [], true) ? $heartbeat->{$field} : null;
    }

    public function history(Request $request, string $deviceId): JsonResponse
    {
        $device = $this->authenticate($request, $deviceId);
        $hours = $request->validate(['hours' => ['sometimes', 'integer', 'min:1', 'max:168']])['hours'] ?? 24;
        $history = $device->heartbeats()
            ->where('reported_at', '>=', now()->subHours((int) $hours))
            ->orderByDesc('reported_at')->orderByDesc('id')
            ->paginate(50);

        return response()->json($history)->header('Cache-Control', 'no-store');
    }

}
