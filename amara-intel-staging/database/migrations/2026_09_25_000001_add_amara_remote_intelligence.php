<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('agent_heartbeats', function (Blueprint $table) {
            $table->string('network_state', 16)->nullable();
            $table->unsignedBigInteger('remote_command_revision')->nullable();
            $table->boolean('remote_work_paused')->nullable();
        });
        Schema::create('agent_remote_commands', function (Blueprint $table) {
            $table->id();
            $table->foreignId('agent_device_id')->constrained()->restrictOnDelete();
            $table->unsignedBigInteger('user_id');
            $table->string('command', 24);
            $table->unsignedBigInteger('revision');
            $table->timestamp('requested_at');
            $table->timestamp('acknowledged_at')->nullable();
            $table->unique(['agent_device_id', 'revision']);
            $table->index(['agent_device_id', 'requested_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('agent_remote_commands');
        Schema::table('agent_heartbeats', fn (Blueprint $table) => $table->dropColumn(['network_state', 'remote_command_revision', 'remote_work_paused']));
    }
};
