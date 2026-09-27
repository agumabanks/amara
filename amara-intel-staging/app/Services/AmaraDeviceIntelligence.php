<?php

namespace App\Services;

use App\Models\AgentDevice;

class AmaraDeviceIntelligence
{
    public function forDevice(AgentDevice $device): array
    {
        $heartbeats = $device->heartbeats()->orderByDesc('reported_at')->limit(288)->get();
        $latest = $heartbeats->first();
        $config = $device->configuration?->config_json ?? [];
        $day = now()->subDay();
        $recent = $heartbeats->filter(fn ($sample) => $sample->reported_at?->gte($day));
        $networkSamples = $recent->filter(fn ($sample) => AmaraFleetStatus::observed($sample, 'network_state') !== null);
        $online = $networkSamples->filter(fn ($sample) => $sample->network_state === 'ONLINE')->count();
        $events = $device->events()->orderByDesc('received_at')->limit(60)->get();
        $logs = $device->logs()->orderByDesc('created_at')->limit(30)->get();
        $verifiedPosts = $device->events()->where('event_type', 'effect.verified')
            ->whereIn('platform', ['tiktok', 'youtube'])
            ->where(fn ($q) => $q->where('operation', 'like', '%post%')->orWhere('operation', 'like', '%publish%')->orWhere('operation', 'like', '%short%'));
        $timeline = $events->map(fn ($event) => [
            'at' => $event->occurred_at?->toIso8601String(),
            'receivedAt' => $event->received_at?->toIso8601String(),
            'title' => str_replace('.', ' ', $event->event_type),
            'detail' => trim(($event->platform ?: 'local').($event->operation ? ' / '.$event->operation : '').($event->status ? ' · '.$event->status : '')),
            'source' => 'Work evidence',
        ])->concat($logs->map(fn ($log) => [
            'at' => $log->created_at?->toIso8601String(), 'receivedAt' => null,
            'title' => $log->module.' / '.$log->action,
            'detail' => ($log->platform ?: 'local').' · '.($log->escalated ? 'Needs review' : ($log->success ? 'Reported success' : 'Reported failure')),
            'source' => 'Legacy report',
        ]))->sortByDesc('at')->take(12)->values()->all();

        return [
            'deviceId' => $device->id,
            'label' => $device->label ?: ($device->agent_name ?: 'Amara').' · '.$device->id,
            'deviceKey' => $device->device_id,
            'shop' => $device->observed_shop_name,
            'shopVerifiedAt' => $device->shop_verified_at?->toIso8601String(),
            'reporting' => AmaraFleetStatus::state($latest),
            'lastSeen' => $latest?->reported_at?->toIso8601String(),
            'build' => $latest?->agent_version,
            'battery' => AmaraFleetStatus::observed($latest, 'battery_level'),
            'charging' => AmaraFleetStatus::observed($latest, 'is_charging'),
            'control' => AmaraFleetStatus::observed($latest, 'accessibility_bound'),
            'pending' => AmaraFleetStatus::observed($latest, 'pending_tasks'),
            'network' => AmaraFleetStatus::observed($latest, 'network_state'),
            'networkSamples24h' => $networkSamples->count(),
            'networkOnline24h' => $online,
            'heartbeatSamples24h' => $recent->count(),
            'posts24h' => (clone $verifiedPosts)->where('received_at', '>=', $day)->count(),
            'posts7d' => (clone $verifiedPosts)->where('received_at', '>=', now()->subDays(7))->count(),
            'lastVerifiedPostAt' => (clone $verifiedPosts)->orderByDesc('received_at')->value('received_at'),
            'recentEvents24h' => $device->events()->where('received_at', '>=', $day)->count(),
            'recentReports24h' => $device->logs()->where('created_at', '>=', $day)->count(),
            'timeline' => $timeline,
            'configFetchedAt' => $device->config_fetched_at?->toIso8601String(),
            'systemLockout' => (bool) ($config['system_lockout'] ?? false),
            'systemLockoutReason' => $config['system_lockout_reason'] ?? null,
            'generatedAt' => now()->toIso8601String(),
        ];
    }
}
