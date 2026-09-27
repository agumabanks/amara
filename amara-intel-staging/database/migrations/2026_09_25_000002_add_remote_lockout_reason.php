<?php
use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;
return new class extends Migration {
    public function up(): void { Schema::table('agent_remote_commands', fn (Blueprint $table) => $table->string('reason', 240)->nullable()->after('command')); }
    public function down(): void { Schema::table('agent_remote_commands', fn (Blueprint $table) => $table->dropColumn('reason')); }
};
