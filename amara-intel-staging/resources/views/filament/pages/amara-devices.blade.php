<x-filament-panels::page>
    @if($this->workspace === 'nodes')
        @once
            <link rel="stylesheet" href="{{ asset('vendor/vis-network/vis-network.min.css') }}">
            <link rel="stylesheet" href="{{ asset('css/amara-fleet-graph.css') }}?v={{ filemtime(public_path('css/amara-fleet-graph.css')) }}">
            <script src="{{ asset('vendor/vis-network/vis-network.min.js') }}" defer></script>
            <script src="{{ asset('js/amara-fleet-graph.js') }}?v={{ filemtime(public_path('js/amara-fleet-graph.js')) }}" defer></script>
        @endonce
    @endif
    <style>
        .amara-stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; }
        .amara-stat { width:100%; padding:16px; border:1px solid #64748b55; border-radius:12px; text-align:left; background:var(--fi-color-gray-50,#f8fafc); }
        .dark .amara-stat { background:#111827; }
        .amara-stat[aria-pressed="true"] { outline:2px solid #14b8a6; outline-offset:2px; }
        .amara-stat strong { display:block; font-size:1.7rem; line-height:1.2; }
        .amara-meter { height:8px; overflow:hidden; border-radius:999px; background:#94a3b855; }
        .amara-meter > span { display:block; height:100%; background:#14b8a6; }
        [x-cloak] { display:none !important; }
    </style>
    <div class="space-y-6" wire:poll.60s x-on:amara-device-selected.window="$nextTick(() => document.getElementById('amara-device-settings')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))">
        <x-filament::section :heading="match ($this->workspace) { 'logs' => 'Activity across Amara devices', 'nodes' => 'Shop and worker nodes', default => 'Amara fleet overview' }">
            @if($this->workspace === 'logs')
                <p>Search reported activity across registered devices, filter review outcomes, and open a device for its work evidence and health history.</p>
            @elseif($this->workspace === 'nodes')
                <p>Browse the observed shop → worker network. Each worker is a registered Amara device account. Browser and paired client nodes will appear only after their pairing records exist.</p>
            @else
                <p>Find a device, inspect its last report, and manage its channels, pairing and operating brief. Device accounts keep their own activity and memory history.</p>
            @endif
            <p class="mt-2 text-sm text-gray-500">Reporting gaps mean unknown status, not a powered-off phone. Channel switches are instructions; they do not prove account login, Android permission or external delivery.</p>
        </x-filament::section>
        @if($this->workspace !== 'logs')
            @php($stats = $this->fleetStats)
            <div class="amara-stats" role="group" aria-label="Fleet reporting summary">
                @foreach(['total' => 'Registered workers', 'recent' => 'Recent heartbeat', 'stale' => 'Stale heartbeat', 'never' => 'Never reported'] as $key => $label)
                    @php($filter = $key === 'total' ? '' : $key)
                    <button type="button" class="amara-stat" wire:click="$set('reporting', '{{ $filter }}')" aria-pressed="{{ $reporting === $filter ? 'true' : 'false' }}">
                        <strong>{{ $stats[$key] }}</strong><span>{{ $label }}</span>
                    </button>
                @endforeach
            </div>
            <p class="text-xs text-gray-500">Counts cover {{ $shop === '' ? 'all registered shops' : 'the selected shop' }}. Recent means a heartbeat within 15 minutes; stale and never reported do not imply the phone is Off.</p>
        @endif
        <x-filament::section heading="Shop → device network">
            <p class="text-sm text-gray-500">Edges reflect the latest signed Terminal observation on each worker. Unverified devices stay separate. Select a shop to inspect its workers.</p>
            <div class="mt-3 flex flex-wrap gap-2">
                <x-filament::button size="sm" :color="$shop === '' ? 'primary' : 'gray'" wire:click="selectShop('')">All shops</x-filament::button>
                @foreach($this->shops as $group)
                    @php($scope=$group->observed_seller_id && $group->observed_shop_id ? $group->observed_seller_id.':'.$group->observed_shop_id : 'unverified')
                    <x-filament::button size="sm" :color="$shop === $scope ? 'primary' : 'gray'" wire:click="selectShop('{{ $scope }}')">
                        {{ $group->observed_shop_name ?: 'Unverified shop' }} · {{ $scope === 'unverified' ? 'unknown' : $scope }} → {{ $group->device_count }} {{ $group->device_count == 1 ? 'device' : 'devices' }}
                    </x-filament::button>
                @endforeach
            </div>
            <div class="mt-3">{{ $this->shops->links() }}</div>
        </x-filament::section>
        @if($this->workspace === 'logs')
        <div class="grid gap-3 md:grid-cols-3">
            <x-filament::input.wrapper class="md:col-span-2"><x-filament::input wire:model.live.debounce.400ms="logSearch" placeholder="Search device, module or action" aria-label="Search activity logs" /></x-filament::input.wrapper>
            <x-filament::input.wrapper><x-filament::input.select wire:model.live="logOutcome" aria-label="Filter log outcome"><option value="">All outcomes</option><option value="review">Needs review</option><option value="failure">Unsuccessful</option><option value="success">Reported success</option></x-filament::input.select></x-filament::input.wrapper>
        </div>
        <x-filament::section heading="Reported activity">
            <p class="mb-3 text-sm text-gray-500">These are device reports, ordered by receipt ID. A reported success is not a verified external receipt. Open a device and use Work evidence for job, attempt and effect records.</p>
            @forelse($this->fleetLogs as $log)
                <div class="border-b py-3 text-sm">
                    <p><x-filament::button size="xs" color="gray" wire:click="selectDevice({{ $log->agent_device_id }})">{{ $log->label ?: $log->device_id }}</x-filament::button> · {{ $log->observed_shop_name ?: 'Unverified shop' }} · <strong>{{ $log->module }} → {{ $log->action }}</strong> · {{ $log->platform ?: 'local' }}</p>
                    <p>{{ $log->escalated ? 'Needs review' : ($log->success ? 'Reported success' : 'Unsuccessful') }} · {{ $log->summary }}</p>
                    <p class="text-xs text-gray-500">Report #{{ $log->id }} · received {{ $log->created_at }}</p>
                </div>
            @empty<p>No activity reports match these filters. A device with no reports may still be working.</p>@endforelse
            <div class="mt-3">{{ $this->fleetLogs->links() }}</div>
        </x-filament::section>
        @else
        <div class="grid gap-3 md:grid-cols-3">
            <x-filament::input.wrapper class="md:col-span-2"><x-filament::input wire:model.live.debounce.400ms="search" placeholder="Find a device or shop" aria-label="Find a device or shop" /></x-filament::input.wrapper>
            <x-filament::input.wrapper><x-filament::input.select wire:model.live="reporting" aria-label="Filter device reporting"><option value="">All reporting states</option><option value="recent">Recent heartbeat</option><option value="stale">Stale heartbeat</option><option value="never">Never reported</option></x-filament::input.select></x-filament::input.wrapper>
        </div>
        @php($devicePage = $this->devices)
        <div x-data="{ mode: '{{ $this->workspace === 'nodes' ? 'graph' : 'list' }}', focused: null }" class="space-y-4">
        @if($this->workspace === 'nodes')
            <div class="flex flex-wrap items-center justify-between gap-2">
                <p class="text-sm text-gray-500">Interactive graph of registered workers and their observed shop links. Select a node for details.</p>
                <div class="flex gap-2" role="group" aria-label="Node display">
                    <button type="button" class="rounded-lg border px-3 py-2 text-sm" @click="mode = 'graph'" :aria-pressed="mode === 'graph'">Graph</button>
                    <button type="button" class="rounded-lg border px-3 py-2 text-sm" @click="mode = 'list'" :aria-pressed="mode === 'list'">List</button>
                </div>
            </div>
            <div id="amara-brain-graph" class="amara-brain-graph" x-show="mode === 'graph'" x-cloak
                 x-on:amara-graph-device="$wire.openDeviceTab($event.detail.id, $event.detail.tab || 'health')"
                 x-on:amara-graph-inspect="$wire.inspectDevice($event.detail.id)"
                 x-on:amara-graph-command="$wire.issueRemoteCommand($event.detail.command, $event.detail.reason || null)"
                 x-on:amara-graph-shop="$wire.selectShop($event.detail.scope)">
                <script id="amara-brain-graph-data" type="application/json">@json($this->nodeGraph)</script>
                <div class="amara-graph-toolbar">
                    <div class="amara-graph-title"><strong>Amara fleet graph</strong><span id="amara-graph-counts">Loading…</span></div>
                    <div class="amara-graph-controls">
                        <label class="sr-only" for="amara-graph-search">Find a graph node</label>
                        <input id="amara-graph-search" type="search" placeholder="Find a node…" autocomplete="off">
                        <label class="sr-only" for="amara-graph-kind">Node type</label>
                        <select id="amara-graph-kind"><option value="all">All nodes</option><option value="shop">Shops</option><option value="device">Workers</option></select>
                        <button type="button" data-graph-action="fit" title="Fit graph to view">Fit</button>
                        <button type="button" data-graph-action="zoom-in" aria-label="Zoom in">+</button>
                        <button type="button" data-graph-action="zoom-out" aria-label="Zoom out">−</button>
                    </div>
                </div>
                <div class="amara-graph-main">
                    <div class="amara-graph-canvas-wrap">
                        <div id="amara-graph-canvas" wire:ignore role="img" aria-label="Interactive graph of Cards registry, observed shops and Amara workers"></div>
                        <div id="amara-graph-message" class="amara-graph-message" role="status">Loading graph…</div>
                        <div class="amara-graph-legend"><strong>Node types</strong><span><i class="registry"></i> Cards registry</span><span><i class="shop"></i> Observed shop</span><span><i class="healthy"></i> Recent and ready</span><span><i class="degraded"></i> Recent, control not ready</span><span><i class="unknown"></i> Stale or unknown</span><small>Dashed link = unverified shop</small></div>
                    </div>
                    <aside id="amara-graph-detail" class="amara-graph-detail" wire:ignore hidden aria-live="polite"></aside>
                </div>
                <p class="amara-graph-footnote">Graph is capped at 200 matching workers for responsiveness. Search or select a shop to narrow it. Lines describe registry and observed shop relationships, not direct device trust. Use List for a keyboard friendly alternative.</p>
            </div>
        @endif
        <div x-show="mode === 'list'" @if($this->workspace === 'nodes') x-cloak @endif>
        <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        @forelse($devicePage as $device)
            @php($heartbeat=$device->latestHeartbeat)
            @php($state=\App\Services\AmaraFleetStatus::state($heartbeat))
            <x-filament::section :heading="$device->label ?: $device->agent_name.' · '.$device->id">
                <p class="font-medium">{{ $device->observed_shop_name ?: $device->business_name }}</p>
                <p class="text-sm">Terminal identity: {{ $device->shop_verified_at ? $device->shop_verified_at->diffForHumans() : 'Not verified' }}</p>
                <p class="text-xs break-all text-gray-500">{{ $device->device_id }}</p>
                <dl class="mt-3 space-y-1 text-sm">
                    <div>Reporting: <strong>{{ ucfirst($state) }}</strong>{{ $state === 'unknown' ? ' · never observed' : ($state === 'stale' ? ' · no recent heartbeat' : ' · recent heartbeat') }}</div>
                    <div>Last heartbeat: {{ $heartbeat?->reported_at?->diffForHumans() ?? 'Not reported' }}{{ $heartbeat?->reported_at ? ' · '.$heartbeat->reported_at : '' }}</div>
                    @if($heartbeat)
                    <div>Reported build: {{ $heartbeat->agent_version ?: 'Unknown' }} · Battery: {{ \App\Services\AmaraFleetStatus::observed($heartbeat, 'battery_level') !== null ? $heartbeat->battery_level.'%' : 'Unknown' }}</div>
                    <div>Last reported phone control: {{ \App\Services\AmaraFleetStatus::observed($heartbeat, 'accessibility_bound') === null ? 'Unknown' : ($heartbeat->accessibility_bound ? 'Connected' : 'Not connected') }}</div>
                    @endif
                    <div>Configuration fetched: {{ $device->config_fetched_at?->diffForHumans() ?? 'Not recorded' }}</div>
                    <div>Memory backup: {{ $device->memorySnapshot?->captured_at?->diffForHumans() ?? 'No backup received' }} · {{ $device->memory_versions_count }} versions</div>
                </dl>
                <div class="mt-4 flex flex-wrap gap-2">
                    <x-filament::button wire:click="openDeviceTab({{ $device->id }}, 'settings')">Manage</x-filament::button>
                    <x-filament::button color="gray" wire:click="openDeviceTab({{ $device->id }}, 'activity')">Logs</x-filament::button>
                    <x-filament::button color="gray" wire:click="openDeviceTab({{ $device->id }}, 'events')">Work evidence</x-filament::button>
                </div>
            </x-filament::section>
        @empty
            <p>No registered devices match. Install Amara and connect it to Cards to register a new account.</p>
        @endforelse
        </div>
        </div>
        <div>{{ $devicePage->links() }}</div>
        </div>
        @endif
        @if($selected=$this->selected)
        <x-filament::section id="amara-device-settings" :heading="'Device: '.($selected->label ?: $selected->device_id)">
            @php($selectedHeartbeat = $selected->latestHeartbeat)
            @php($selectedBattery = \App\Services\AmaraFleetStatus::observed($selectedHeartbeat, 'battery_level'))
            <div class="mb-4 grid gap-3 sm:grid-cols-3 text-sm">
                <div class="amara-stat"><strong class="text-base">{{ ucfirst(\App\Services\AmaraFleetStatus::state($selectedHeartbeat)) }}</strong><span>Reporting · {{ $selectedHeartbeat?->reported_at?->diffForHumans() ?? 'never' }}</span></div>
                <div class="amara-stat"><strong class="text-base">{{ $selectedBattery === null ? 'Unknown' : $selectedBattery.'%' }}</strong><span>Last observed battery</span>@if($selectedBattery !== null)<div class="amara-meter mt-2" role="meter" aria-label="Last observed battery" aria-valuemin="0" aria-valuemax="100" aria-valuenow="{{ $selectedBattery }}"><span style="width:{{ max(0, min(100, $selectedBattery)) }}%"></span></div>@endif</div>
                <div class="amara-stat"><strong class="text-base">{{ \App\Services\AmaraFleetStatus::observed($selectedHeartbeat, 'pending_tasks') ?? 'Unknown' }}</strong><span>Last reported pending tasks</span></div>
            </div>
            <p class="mb-4 text-sm">Last verified Terminal shop: <strong>{{ $selected->observed_shop_name ?: 'Not verified' }}</strong> · seller {{ $selected->observed_seller_id ?: 'unknown' }} / shop {{ $selected->observed_shop_id ?: 'unknown' }}. {{ $selected->shop_verified_at?->diffForHumans() }}. This is an observation; expired proof blocks fresh commercial actions.</p>
            <p class="mb-4 text-sm">Legacy registered business: <strong>{{ $selected->business_name }}</strong>. Terminal account linking: {{ $selected->soko_account_id ? 'Recorded; verify before use' : 'Not linked' }}. Shop reassignment is unavailable here to prevent mixing an existing account’s memories. A business name alone does not verify ownership.</p>
            <div class="flex flex-wrap gap-2 mb-4">
                @foreach(['activity'=>'Activity', 'events'=>'Work evidence', 'health'=>'Health history', 'shops'=>'Shop history', 'memory'=>'Memory history', 'settings'=>'Settings & pairing'] as $tab=>$label)
                    <x-filament::button size="sm" :color="$detailTab === $tab ? 'primary' : 'gray'" wire:click="showTab('{{ $tab }}')">{{ $label }}</x-filament::button>
                @endforeach
            </div>
        </x-filament::section>
        @if($detailTab === 'settings')
        <x-filament::section heading="Settings & pairing">
            <div class="mb-4 rounded-lg border border-warning-300 bg-warning-50 p-3 dark:bg-warning-950">
                <p class="font-medium">Remote work control</p>
                <p class="mb-2 text-sm">Queues a typed pause or resume for Amara’s work loop. It never powers off the phone and cannot override the owner’s local Off switch.</p>
                <div class="flex flex-wrap gap-2">
                    <x-filament::button color="warning" wire:click="issueRemoteCommand('pause_work')" wire:confirm="Pause Amara work on this device?">Pause Amara work</x-filament::button>
                    <x-filament::button color="gray" wire:click="issueRemoteCommand('resume_work')" wire:confirm="Resume Amara work on this device?">Resume Amara work</x-filament::button>
                    <x-filament::button color="danger" wire:click="issueRemoteCommand('system_lockout')" wire:confirm="Lock this device out of Amara work until an admin unlocks it?">System lockout</x-filament::button>
                    <x-filament::button color="success" wire:click="issueRemoteCommand('system_unlock')" wire:confirm="Unlock this device?">Unlock</x-filament::button>
                </div>
                <label class="mt-2 block text-sm">Lockout reason (required for system lockout)<x-filament::input.wrapper><x-filament::input wire:model="lockoutReason" maxlength="240" placeholder="Subscription past due, fraud review, etc." /></x-filament::input.wrapper></label>
                <p class="mt-2 text-xs text-gray-500">Status changes to acknowledged after the next authenticated heartbeat reports the command revision and matching pause and lockout states.</p>
            </div>
            <div class="mb-4 space-y-2">
                <p class="text-sm">Reconnect this device without deleting its memories. Generate a code, then enter it in Amara Settings → Connect to Cards admin on the matching device. The old credential is replaced only when that device redeems the code.</p>
                <x-filament::button color="gray" wire:click="issuePairingCode">Generate 10-minute pairing code</x-filament::button>
                @if($pairingCode)<p class="font-mono break-all" role="status">{{ $pairingCode }}</p><p class="text-sm">Use once on this device. Generating another code invalidates this one.</p>@endif
            </div>
            <form wire:submit="save" class="space-y-4">
                <label class="block">Device name<x-filament::input.wrapper><x-filament::input wire:model="settings.label" placeholder="e.g. Osa Gadgets · TPS450M" /></x-filament::input.wrapper></label>
                <label class="block">Manager WhatsApp number<x-filament::input.wrapper><x-filament::input wire:model="settings.manager_whatsapp" placeholder="+256…" /></x-filament::input.wrapper></label>
                <label class="block">Shop needs and operating brief<textarea wire:model="settings.business_brief" rows="4" maxlength="4000" class="w-full rounded-lg border-gray-300 dark:bg-gray-900" placeholder="Priorities, approved service details, customer tone and when to ask the manager"></textarea></label>
                <div class="grid gap-3 md:grid-cols-2">
                @foreach(['tiktok_test_mode'=>'TikTok publications','tiktok_comments_enabled'=>'TikTok comment monitoring','tiktok_social_enabled'=>'TikTok community activity','tiktok_stories_enabled'=>'TikTok Stories','tiktok_notification_replies_enabled'=>'Replies to own TikTok comments','whatsapp_automation_enabled'=>'WhatsApp automation','whatsapp_inbound_enabled'=>'WhatsApp customer replies','whatsapp_groups_enabled'=>'WhatsApp groups','whatsapp_followups_enabled'=>'WhatsApp follow-ups'] as $key=>$label)
                    <label class="block">{{ $label }}<x-filament::input.wrapper><x-filament::input.select wire:model="settings.{{ $key }}"><option value="">Keep current device setting</option><option value="1">On</option><option value="0">Off</option></x-filament::input.select></x-filament::input.wrapper></label>
                @endforeach
                </div>
                @if($errors->any())<p class="text-danger-600">{{ $errors->first() }}</p>@endif
                <p class="text-sm text-gray-500">These changes reach compatible builds on their next config sync. They do not turn on a device that its owner switched Off, grant Android permissions, or send a message. Memory backup remains an owner opt-in setting.</p>
                <x-filament::button type="submit" wire:loading.attr="disabled">Save this device’s settings</x-filament::button>
            </form>
        </x-filament::section>
        @endif
        @if($detailTab === 'events')
        <x-filament::section heading="Work evidence">
            <p class="mb-3 text-sm text-gray-500">Device → job → attempt → effect, recorded by authenticated Amara event sync. Received time shows delayed uploads separately from action time. A dispatched effect is not a verified publication.</p>
            @if($selectedJobId)
                <p class="mb-3 text-sm">Job <span class="font-mono break-all">{{ $selectedJobId }}</span> <x-filament::button size="xs" color="gray" wire:click="selectJob(null)">Show all jobs</x-filament::button></p>
            @endif
            @forelse($this->events as $event)
                <div class="border-b py-3 text-sm">
                    <p><strong>{{ $event->event_type }}</strong> · {{ $event->status ?: 'No outcome' }} · {{ $event->platform ?: 'local' }}{{ $event->operation ? ' / '.$event->operation : '' }}</p>
                    <p>Shop {{ $event->seller_id }}:{{ $event->shop_id }} · revision {{ $event->binding_revision }} · action {{ $event->occurred_at }} · received {{ $event->received_at }}</p>
                    @if($event->job_id)<p>Job <x-filament::button size="xs" color="gray" wire:click="selectJob('{{ $event->job_id }}')">{{ $event->job_id }}</x-filament::button></p>@endif
                    @if($event->attempt_id)<p>Attempt <span class="font-mono break-all">{{ $event->attempt_id }}</span></p>@endif
                    @if($event->effect_id)<p>Effect <span class="font-mono break-all">{{ $event->effect_id }}</span></p>@endif
                    @if($event->reason_code)<p>Reason {{ $event->reason_code }}</p>@endif
                    @if($event->facts)<p class="text-xs text-gray-500">Allowlisted facts: {{ collect($event->facts)->map(fn ($value, $key) => $key.'='.($value === true ? 'true' : ($value === false ? 'false' : $value)))->implode(' · ') }}</p>@endif
                </div>
            @empty<p>No canonical work events received for this {{ $selectedJobId ? 'job' : 'device' }}. Older activity reports appear in Activity.</p>@endforelse
            @if($this->events)<div class="mt-3">{{ $this->events->links() }}</div>@endif
        </x-filament::section>
        @endif
        @if($detailTab === 'shops')
        <x-filament::section heading="Signed shop history">
            <p class="mb-3 text-sm text-gray-500">Each revision begins when Cards accepts a signed Terminal shop observation. Legacy backfill records only the last shop known before history began; earlier transitions cannot be reconstructed.</p>
            @forelse($this->bindings as $binding)
                <div class="border-b py-2 text-sm">
                    <p>Revision {{ $binding->revision }} → <strong>{{ $binding->shop_name }}</strong> · seller {{ $binding->seller_id }} / shop {{ $binding->shop_id }}</p>
                    <p>First recorded {{ $binding->started_at }} · last verified {{ $binding->last_verified_at }} · {{ $binding->ended_at ? 'ended '.$binding->ended_at : 'current' }}{{ $binding->legacy_latest_only ? ' · legacy latest-only backfill' : '' }}</p>
                </div>
            @empty<p>No signed shop observation has been recorded for this device.</p>@endforelse
        </x-filament::section>
        @endif
        @if($detailTab === 'memory')
        <x-filament::section heading="Memory history">
            <p class="mb-3 text-sm">Encrypted snapshots belong only to this device. New backups retain earlier versions; identical consecutive uploads do not create another version. Raw customer memories and credentials are not displayed here.</p>
            @forelse($this->versions as $version)<p class="text-sm">Version {{ $version->id }} · schema {{ $version->schema_version }} · {{ $version->captured_at }} · {{ substr($version->content_hash,0,12) }}</p>
            @empty<p>No versioned backups received yet. An existing current backup is preserved when the next backup arrives.</p>@endforelse
        </x-filament::section>
        @endif
        @if($detailTab === 'activity')
        <x-filament::section heading="Recent reported activity">
            <p class="mb-3 text-sm text-gray-500">Older device reports. A reported success is not a verified external receipt. New shop-scoped job and attempt events appear under Work evidence; external receipt mapping is still being built.</p>
            @forelse($this->recentLogs as $log)
                <div class="border-b py-2"><p>{{ $log->module }} → {{ $log->action }} · {{ $log->platform ?: 'local' }} · {{ $log->escalated ? 'Needs review' : ($log->success ? 'Reported success' : 'Unsuccessful') }}</p><p class="text-sm">{{ $log->summary }}</p><p class="text-xs text-gray-500">Report #{{ $log->id }} · {{ $log->created_at }}</p></div>
            @empty<p>No activity reports received. This does not prove the device is idle; telemetry may be disabled.</p>@endforelse
            @if($this->recentLogs)<div class="mt-3">{{ $this->recentLogs->links() }}</div>@endif
        </x-filament::section>
        @endif
        @if($detailTab === 'health')
        <x-filament::section heading="Health history">
            <p class="mb-3 text-sm text-gray-500">Only fields named in each heartbeat's observed-fields list are shown as measured. No heartbeat means unknown, not Off.</p>
            @forelse($this->heartbeatHistory as $sample)
                <div class="border-b py-2 text-sm">
                    <p>{{ $sample->reported_at }} · {{ $sample->agent_version ?: 'Unknown build' }} · heartbeat #{{ $sample->id }}</p>
                    <p>Battery {{ \App\Services\AmaraFleetStatus::observed($sample, 'battery_level') !== null ? $sample->battery_level.'%' : 'unknown' }} · Control {{ \App\Services\AmaraFleetStatus::observed($sample, 'accessibility_bound') === null ? 'unknown' : ($sample->accessibility_bound ? 'bound' : 'unbound') }} · Pending {{ \App\Services\AmaraFleetStatus::observed($sample, 'pending_tasks') ?? 'unknown' }}</p>
                </div>
            @empty<p>No heartbeat history received. This does not prove that the worker is Off.</p>@endforelse
            @if($this->heartbeatHistory)<div class="mt-3">{{ $this->heartbeatHistory->links() }}</div>@endif
        </x-filament::section>
        @endif
        @if($detailTab === 'settings')
        <x-filament::section heading="Admin change history">
            @forelse($this->changes as $change)<p class="text-sm">{{ $change->created_at }} · {{ $change->user_id ? 'Admin '.$change->user_id : 'Device / authorized operator' }} · {{ implode(', ',json_decode($change->changed_fields,true)) }}</p>
            @empty<p>No admin changes recorded for this device.</p>@endforelse
        </x-filament::section>
        @endif
        @endif
    </div>
</x-filament-panels::page>
