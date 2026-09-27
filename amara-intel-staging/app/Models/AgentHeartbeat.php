<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class AgentHeartbeat extends Model
{
    protected $fillable = [
        'agent_device_id',
        'device_id',
        'agent_version',
        'uptime_ms',
        'accessibility_bound',
        'groq_healthy',
        'pending_tasks',
        'completed_tasks_24h',
        'failed_tasks_24h',
        'battery_level',
        'is_charging',
        'memory_usage_mb',
        'network_state',
        'remote_command_revision',
        'remote_work_paused',
        'system_lockout',
        'observed_fields',
        'reported_at',
    ];

    protected $casts = [
        'accessibility_bound' => 'boolean',
        'groq_healthy' => 'boolean',
        'is_charging' => 'boolean',
        'uptime_ms' => 'integer',
        'pending_tasks' => 'integer',
        'completed_tasks_24h' => 'integer',
        'failed_tasks_24h' => 'integer',
        'battery_level' => 'integer',
        'memory_usage_mb' => 'integer',
        'remote_command_revision' => 'integer',
        'remote_work_paused' => 'boolean',
        'system_lockout' => 'boolean',
        'observed_fields' => 'array',
        'reported_at' => 'datetime',
    ];

    public function device(): BelongsTo
    {
        return $this->belongsTo(AgentDevice::class, 'agent_device_id');
    }
}
