<?php

namespace App\Services;

use App\Models\AgentConfig;
use App\Models\AgentDevice;
use Illuminate\Support\Facades\DB;

class AmaraRemoteCommands
{
    public function issue(AgentDevice $device, string $command, int $actor, ?string $reason = null): array
    {
        abort_unless(in_array($command, ['pause_work', 'resume_work', 'system_lockout', 'system_unlock'], true), 422);
        if ($command === 'system_lockout') {
            abort_unless(is_string($reason) && trim($reason) !== '', 422);
            $reason = mb_substr(trim($reason), 0, 240);
        }

        return DB::transaction(function () use ($device, $command, $actor, $reason) {
            $device = AgentDevice::whereKey($device->id)->lockForUpdate()->firstOrFail();
            $config = AgentConfig::firstOrCreate(['agent_device_id' => $device->id], ['config_json' => [], 'updated_at' => now()]);
            $values = $config->config_json ?? [];
            $revision = ((int) ($values['remote_command_revision'] ?? 0)) + 1;
            $values['remote_command_revision'] = $revision;
            if ($command === 'pause_work' || $command === 'resume_work') $values['remote_work_paused'] = $command === 'pause_work';
            if ($command === 'system_lockout' || $command === 'system_unlock') $values['system_lockout'] = $command === 'system_lockout';
            if ($command === 'system_lockout') $values['system_lockout_reason'] = $reason;
            if ($command === 'system_unlock') $values['system_lockout_reason'] = null;
            $config->update(['config_json' => $values, 'updated_at' => now()]);
            $id = DB::table('agent_remote_commands')->insertGetId([
                'agent_device_id' => $device->id, 'user_id' => $actor,
                'command' => $command, 'reason' => $reason, 'revision' => $revision, 'requested_at' => now(),
            ]);
            return ['id' => $id, 'command' => $command, 'revision' => $revision, 'state' => 'pending'];
        });
    }
}
