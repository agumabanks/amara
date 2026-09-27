<?php

namespace App\Filament\Pages;

use App\Models\AgentDevice;
use App\Services\AgentDeviceManagement;
use Filament\Notifications\Notification;
use Filament\Pages\Page;
use Livewire\WithPagination;
use Illuminate\Support\Facades\DB;

class AmaraDevices extends Page
{
    use \App\Filament\Traits\AppNavigationFilter;
    use WithPagination;

    protected static ?string $navigationIcon = 'heroicon-o-device-phone-mobile';

    protected static ?string $navigationGroup = 'Devices';

    protected static ?string $navigationLabel = 'Fleet overview';

    protected static ?int $navigationSort = 1;

    protected static string $section = 'fleet';

    protected static ?string $title = 'Amara Devices';

    protected static ?string $slug = 'amara-devices';

    protected static string $view = 'filament.pages.amara-devices';

    public string $search = '';

    public string $reporting = '';

    public string $logSearch = '';

    public string $logOutcome = '';

    public string $shop = '';

    public string $detailTab = 'activity';

    public ?string $pairingCode = null;

    public ?int $selectedId = null;

    public ?string $selectedJobId = null;

    public array $settings = [];

    public string $remoteCommand = '';
    public string $lockoutReason = '';

    public static function canAccess(): bool
    {
        return auth()->user()?->isAdmin() || auth()->user()?->isSuperAdmin();
    }

    public function getWorkspaceProperty(): string
    {
        return static::$section;
    }

    public function selectDevice(int $id): void
    {
        abort_unless(static::canAccess(), 403);
        $device = AgentDevice::findOrFail($id);
        $config = $device->configuration?->config_json ?? [];
        $this->selectedId = $device->id;
        $this->pairingCode = null;
        $this->detailTab = 'activity';
        $this->selectedJobId = null;
        $this->resetPage('activityPage');
        $this->resetPage('heartbeatPage');
        $this->resetPage('eventsPage');
        $this->settings = ['label' => $device->label ?? '', 'manager_whatsapp' => $config['manager_whatsapp'] ?? '', 'business_brief' => $config['business_brief'] ?? ''];
        foreach (AgentDeviceManagement::CHANNELS as $key) {
            $this->settings[$key] = isset($config[$key]) ? ($config[$key] ? '1' : '0') : '';
        }
        $this->resetValidation();
        $this->dispatch('amara-device-selected');
    }

    public function openDeviceTab(int $id, string $tab): void
    {
        abort_unless(in_array($tab, ['activity', 'events', 'health', 'settings'], true), 404);
        $this->selectDevice($id);
        $this->detailTab = $tab;
    }

    public function inspectDevice(int $id): void
    {
        abort_unless(static::canAccess(), 403);
        $device = AgentDevice::findOrFail($id);
        $this->selectedId = $id;
        $this->dispatch('amara-intelligence-ready', intelligence: app(\App\Services\AmaraDeviceIntelligence::class)->forDevice($device));
    }

    public function issueRemoteCommand(string $command, ?string $reason = null): void
    {
        abort_unless(static::canAccess(), 403);
        abort_unless($this->selectedId, 422);
        abort_unless(in_array($command, ['pause_work', 'resume_work', 'system_lockout', 'system_unlock'], true), 422);
        $reason = $reason ?? $this->lockoutReason;
        app(\App\Services\AmaraRemoteCommands::class)->issue(AgentDevice::findOrFail($this->selectedId), $command, (int) auth()->id(), $reason);
        $this->remoteCommand = '';
        $this->lockoutReason = '';
        Notification::make()->title('Command queued')->body('The device must fetch configuration and acknowledge this revision.')->success()->send();
    }

    public function updatedSearch(): void
    {
        $this->resetPage('devicesPage');
    }

    public function updatedReporting(): void
    {
        if (! in_array($this->reporting, ['', 'recent', 'stale', 'never'], true)) {
            $this->reporting = '';
        }
        $this->resetPage('devicesPage');
    }

    public function updatedLogSearch(): void
    {
        $this->resetPage('fleetLogsPage');
    }

    public function updatedLogOutcome(): void
    {
        if (! in_array($this->logOutcome, ['', 'review', 'success', 'failure'], true)) {
            $this->logOutcome = '';
        }
        $this->resetPage('fleetLogsPage');
    }

    public function updatedShop(): void
    {
        if ($this->shop !== '' && $this->shop !== 'unverified' && ! preg_match('/^[1-9][0-9]*:[1-9][0-9]*$/', $this->shop)) {
            $this->shop = '';
        }
        $this->resetPage('devicesPage');
        $this->resetPage('fleetLogsPage');
        $this->selectedId = null;
    }

    public function selectShop(string $scope): void
    {
        abort_unless(static::canAccess(), 403);
        abort_unless($scope === '' || $scope === 'unverified' || preg_match('/^[1-9][0-9]*:[1-9][0-9]*$/', $scope), 422);
        $this->shop = $scope;
        $this->resetPage('devicesPage');
        $this->resetPage('fleetLogsPage');
        $this->selectedId = null;
    }

    public function showTab(string $tab): void
    {
        abort_unless(static::canAccess(), 403);
        abort_unless(in_array($tab, ['activity', 'events', 'health', 'shops', 'memory', 'settings'], true), 404);
        $this->detailTab = $tab;
    }

    public function selectJob(?string $jobId): void
    {
        abort_unless(static::canAccess(), 403);
        abort_unless($jobId === null || \Illuminate\Support\Str::isUuid($jobId), 422);
        $this->selectedJobId = $jobId;
        $this->resetPage('eventsPage');
    }

    public function issuePairingCode(): void
    {
        abort_unless(static::canAccess(), 403);
        $this->pairingCode = app(\App\Services\AgentDevicePairing::class)->issue(AgentDevice::findOrFail($this->selectedId), auth()->id());
    }

    public function save(): void
    {
        abort_unless(static::canAccess(), 403);
        $device = AgentDevice::findOrFail($this->selectedId);
        app(AgentDeviceManagement::class)->save($device, $this->settings, auth()->id());
        Notification::make()->title('Device configuration saved')->body('Applied on the next successful configuration sync. The owner’s local Off switch remains authoritative.')->success()->send();
    }

    public function getDevicesProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->filteredDevicesQuery()
            ->with(['latestHeartbeat', 'memorySnapshot:id,agent_device_id,captured_at'])->withCount('memoryVersions')
            ->orderByDesc('updated_at')->orderByDesc('id')->paginate(24, pageName: 'devicesPage');
    }

    private function filteredDevicesQuery()
    {
        return AgentDevice::query()
            ->when($this->search !== '', fn ($q) => $q->where(fn ($q) => $q->where('label', 'like', '%'.$this->search.'%')->orWhere('device_id', 'like', '%'.$this->search.'%')->orWhere('business_name', 'like', '%'.$this->search.'%')->orWhere('observed_shop_name', 'like', '%'.$this->search.'%')))
            ->when($this->reporting === 'recent', fn ($q) => $q->whereHas('latestHeartbeat', fn ($h) => $h->where('reported_at', '>=', now()->subMinutes(15))))
            ->when($this->reporting === 'stale', fn ($q) => $q->whereHas('latestHeartbeat', fn ($h) => $h->where('reported_at', '<', now()->subMinutes(15))))
            ->when($this->reporting === 'never', fn ($q) => $q->whereDoesntHave('latestHeartbeat'))
            ->when($this->shop !== '', function ($q) {
                if ($this->shop === 'unverified') {
                    $q->where(fn ($q) => $q->whereNull('observed_seller_id')->orWhereNull('observed_shop_id'));
                } else {
                    [$seller, $shop] = explode(':', $this->shop, 2);
                    $q->where('observed_seller_id', $seller)->where('observed_shop_id', $shop);
                }
            });
    }

    public function getNodeGraphProperty(): array
    {
        abort_unless(static::canAccess(), 403);

        // Keep the force layout bounded. Search and shop filters can reach the rest of the fleet.
        $query = $this->filteredDevicesQuery();
        $total = (clone $query)->count();
        $workers = $query->select('id', 'device_id', 'agent_name', 'label', 'business_name',
            'observed_seller_id', 'observed_shop_id', 'observed_shop_name', 'updated_at')
            ->with('latestHeartbeat')->orderByDesc('updated_at')->orderByDesc('id')->limit(200)->get();

        $nodes = [[
            'id' => 'registry', 'kind' => 'registry', 'label' => 'Cards registry',
            'detail' => 'Registered Amara workers and their last observed shop association.',
        ]];
        $edges = [];
        $shops = [];
        foreach ($workers as $worker) {
            $verified = $worker->observed_seller_id && $worker->observed_shop_id;
            $shopKey = $verified ? 'shop:'.$worker->observed_seller_id.':'.$worker->observed_shop_id : 'shop:unverified';
            if (! isset($shops[$shopKey])) {
                $shops[$shopKey] = true;
                $nodes[] = [
                    'id' => $shopKey, 'kind' => 'shop',
                    'label' => $verified ? ($worker->observed_shop_name ?: 'Shop '.$worker->observed_shop_id) : 'Unverified shop',
                    'scope' => $verified ? $worker->observed_seller_id.':'.$worker->observed_shop_id : 'unverified',
                    'verified' => (bool) $verified,
                ];
                $edges[] = ['from' => 'registry', 'to' => $shopKey, 'kind' => 'registry'];
            }

            $heartbeat = $worker->latestHeartbeat;
            $nodes[] = [
                'id' => 'device:'.$worker->id, 'kind' => 'device', 'deviceId' => $worker->id,
                'label' => $worker->label ?: ($worker->agent_name ?: 'Amara').' · '.$worker->id,
                'deviceKey' => $worker->device_id,
                'state' => \App\Services\AmaraFleetStatus::state($heartbeat),
                'lastSeen' => $heartbeat?->reported_at?->toIso8601String(),
                'battery' => \App\Services\AmaraFleetStatus::observed($heartbeat, 'battery_level'),
                'pending' => \App\Services\AmaraFleetStatus::observed($heartbeat, 'pending_tasks'),
            ];
            $edges[] = ['from' => $shopKey, 'to' => 'device:'.$worker->id,
                'kind' => $verified ? 'observed_shop' : 'unverified'];
        }

        return ['nodes' => $nodes, 'edges' => $edges, 'shownWorkers' => $workers->count(),
            'totalWorkers' => $total, 'shopCount' => count($shops), 'limit' => 200];
    }

    public function getShopsProperty()
    {
        abort_unless(static::canAccess(), 403);

        return DB::table('agent_devices')->selectRaw('observed_seller_id, observed_shop_id, max(observed_shop_name) as observed_shop_name, count(*) as device_count')
            ->groupBy('observed_seller_id', 'observed_shop_id')
            ->orderByDesc('device_count')->orderBy('observed_shop_id')->paginate(20, pageName: 'shopsPage');
    }

    public function getFleetStatsProperty(): array
    {
        abort_unless(static::canAccess(), 403);

        $devices = AgentDevice::query()->when($this->shop !== '', function ($query) {
            if ($this->shop === 'unverified') {
                $query->where(fn ($q) => $q->whereNull('observed_seller_id')->orWhereNull('observed_shop_id'));
            } else {
                [$seller, $shop] = explode(':', $this->shop, 2);
                $query->where('observed_seller_id', $seller)->where('observed_shop_id', $shop);
            }
        });
        $threshold = now()->subMinutes(15);

        return [
            'total' => (clone $devices)->count(),
            'recent' => (clone $devices)->whereHas('latestHeartbeat', fn ($q) => $q->where('reported_at', '>=', $threshold))->count(),
            'stale' => (clone $devices)->whereHas('latestHeartbeat', fn ($q) => $q->where('reported_at', '<', $threshold))->count(),
            'never' => (clone $devices)->whereDoesntHave('latestHeartbeat')->count(),
        ];
    }

    public function getSelectedProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->selectedId ? AgentDevice::with(['latestHeartbeat', 'memorySnapshot:id,agent_device_id,captured_at'])->findOrFail($this->selectedId) : null;
    }

    public function getFleetLogsProperty()
    {
        abort_unless(static::canAccess(), 403);

        return DB::table('agent_logs as logs')
            ->join('agent_devices as devices', 'devices.id', '=', 'logs.agent_device_id')
            ->select('logs.id', 'logs.agent_device_id', 'logs.module', 'logs.action', 'logs.platform',
                'logs.summary', 'logs.success', 'logs.escalated', 'logs.created_at',
                'devices.label', 'devices.device_id', 'devices.observed_shop_name')
            ->when($this->shop !== '', function ($query) {
                if ($this->shop === 'unverified') {
                    $query->where(fn ($q) => $q->whereNull('devices.observed_seller_id')->orWhereNull('devices.observed_shop_id'));
                } else {
                    [$seller, $shop] = explode(':', $this->shop, 2);
                    $query->where('devices.observed_seller_id', $seller)->where('devices.observed_shop_id', $shop);
                }
            })
            ->when($this->logSearch !== '', fn ($q) => $q->where(fn ($q) => $q
                ->where('devices.label', 'like', '%'.$this->logSearch.'%')
                ->orWhere('devices.device_id', 'like', '%'.$this->logSearch.'%')
                ->orWhere('logs.module', 'like', '%'.$this->logSearch.'%')
                ->orWhere('logs.action', 'like', '%'.$this->logSearch.'%')))
            ->when($this->logOutcome === 'review', fn ($q) => $q->where('logs.escalated', true))
            ->when($this->logOutcome === 'success', fn ($q) => $q->where('logs.success', true)->where('logs.escalated', false))
            ->when($this->logOutcome === 'failure', fn ($q) => $q->where('logs.success', false)->where('logs.escalated', false))
            ->orderByDesc('logs.id')->paginate(25, pageName: 'fleetLogsPage');
    }

    public function getRecentLogsProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->selected?->logs()->select('id', 'agent_device_id', 'module', 'action', 'platform', 'summary', 'success', 'escalated', 'created_at')
            ->orderByDesc('created_at')->orderByDesc('id')->paginate(25, pageName: 'activityPage');
    }

    public function getHeartbeatHistoryProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->selected?->heartbeats()->select('id', 'agent_device_id', 'reported_at', 'agent_version', 'battery_level', 'is_charging', 'accessibility_bound', 'pending_tasks', 'completed_tasks_24h', 'failed_tasks_24h', 'observed_fields')
            ->orderByDesc('reported_at')->orderByDesc('id')->paginate(25, pageName: 'heartbeatPage');
    }

    public function getVersionsProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->selected?->memoryVersions()->select('id', 'agent_device_id', 'schema_version', 'captured_at', 'content_hash')->latest('id')->limit(15)->get() ?? collect();
    }

    public function getBindingsProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->selected?->shopBindings()->orderByDesc('revision')->limit(25)->get() ?? collect();
    }

    public function getEventsProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->selected?->events()->select('id', 'agent_device_id', 'event_id', 'event_type', 'binding_revision',
            'seller_id', 'shop_id', 'job_id', 'attempt_id', 'effect_id', 'platform', 'operation', 'status',
            'reason_code', 'occurred_at', 'received_at', 'facts')
            ->when($this->selectedJobId, fn ($q) => $q->where('job_id', $this->selectedJobId))
            ->orderByDesc('occurred_at')->orderByDesc('id')->paginate(25, pageName: 'eventsPage');
    }

    public function getChangesProperty()
    {
        abort_unless(static::canAccess(), 403);

        return $this->selectedId ? DB::table('agent_admin_changes')->where('agent_device_id', $this->selectedId)->latest('id')->limit(15)->get() : collect();
    }
}
