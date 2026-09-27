/* Amara fleet graph. Uses the same vis-network interaction model as Sanaa Brain. */
(() => {
    'use strict';

    const palette = {
        registry: '#8b5cf6', shop: '#14b8a6', healthy: '#22c55e',
        degraded: '#f59e0b', online: '#3b82f6', stale: '#94a3b8', unknown: '#64748b',
    };
    let current = null;
    let hooked = false;
    let scheduled = false;

    function scheduleSync() {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(() => { scheduled = false; sync(); });
    }

    function showMessage(root, message) {
        const element = root.querySelector('#amara-graph-message');
        element.textContent = message;
        element.hidden = false;
    }

    function hideMessage(root) {
        root.querySelector('#amara-graph-message').hidden = true;
    }

    function colorFor(node) {
        return node.kind === 'device' ? (palette[node.state] || palette.unknown) : palette[node.kind];
    }

    function escapeHtml(value) {
        return String(value).replace(/[&<>"']/g, character => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
        })[character]);
    }

    function makeNode(node) {
        const color = colorFor(node);
        return {
            id: node.id,
            label: String(node.label || '').slice(0, 42),
            title: escapeHtml(node.label || ''),
            shape: node.kind === 'registry' ? 'diamond' : (node.kind === 'shop' ? 'hexagon' : 'dot'),
            size: node.kind === 'registry' ? 27 : (node.kind === 'shop' ? 23 : 16),
            color: { background: color, border: '#e2e8f0', highlight: { background: color, border: '#fff' } },
            font: { color: '#e2e8f0', size: node.kind === 'device' ? 11 : 13, face: 'system-ui' },
            borderWidth: 1.5,
        };
    }

    function makeEdge(edge) {
        const unverified = edge.kind === 'unverified';
        return {
            from: edge.from, to: edge.to,
            color: { color: unverified ? '#64748b' : (edge.kind === 'registry' ? '#8b5cf6' : '#14b8a6'),
                highlight: '#f8fafc' },
            dashes: unverified,
            width: edge.kind === 'registry' ? 2 : 1.6,
            smooth: { type: 'continuous', roundness: .25 },
            arrows: { to: { enabled: true, scaleFactor: .35 } },
        };
    }

    function addField(list, label, value) {
        const row = document.createElement('div');
        const term = document.createElement('dt');
        const detail = document.createElement('dd');
        term.textContent = label;
        detail.textContent = value == null || value === '' ? 'Unknown' : String(value);
        row.append(term, detail);
        list.append(row);
    }

    function addButton(panel, label, callback) {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.addEventListener('click', callback);
        panel.append(button);
    }

    function section(panel, title) {
        const heading = document.createElement('h4');
        heading.textContent = title;
        panel.append(heading);
        return heading;
    }

    function time(value) {
        return value ? new Date(value).toLocaleString() : 'Not recorded';
    }

    function renderIntelligence(data) {
        if (!current || current.selectedDevice !== data.deviceId) return;
        const panel = current.root.querySelector('#amara-graph-detail');
        panel.querySelector('.amara-intelligence')?.remove();
        const content = document.createElement('div');
        content.className = 'amara-intelligence';
        section(content, 'Device intelligence');
        const status = document.createElement('p');
        status.className = 'amara-intelligence-note';
        status.textContent = `Updated ${time(data.generatedAt)} · evidence from this device only`;
        content.append(status);
        const health = document.createElement('dl');
        addField(health, 'Build / phone control', `${data.build || 'Unknown'} · ${data.control == null ? 'unknown' : (data.control ? 'connected' : 'not connected')}`);
        addField(health, 'Battery', data.battery == null ? 'Unknown' : `${data.battery}%${data.charging == null ? '' : (data.charging ? ' · charging' : ' · not charging')}`);
        addField(health, 'Internet now', data.network || 'Unknown · older builds do not report this');
        addField(health, 'Internet samples, 24h', data.networkSamples24h ? `${data.networkOnline24h}/${data.networkSamples24h} reported ONLINE` : 'No measured samples');
        addField(health, 'Heartbeats received, 24h', data.heartbeatSamples24h);
        addField(health, 'Pending tasks', data.pending);
        addField(health, 'Last config fetch', time(data.configFetchedAt));
        addField(health, 'System access', data.systemLockout ? `LOCKED · ${data.systemLockoutReason || 'No reason recorded'}` : 'Enabled');
        content.append(health);
        section(content, 'Publishing & activity');
        const activity = document.createElement('dl');
        addField(activity, 'Verified post effects', `${data.posts24h} in 24h · ${data.posts7d} in 7d`);
        addField(activity, 'Last verified post', time(data.lastVerifiedPostAt));
        addField(activity, 'Work events, 24h', data.recentEvents24h);
        addField(activity, 'Legacy reports, 24h', data.recentReports24h);
        content.append(activity);
        const caveat = document.createElement('p');
        caveat.className = 'amara-intelligence-note';
        caveat.textContent = 'Post counts use received verified effects; they do not measure views or sales. Missing telemetry means unknown.';
        content.append(caveat);
        section(content, 'Remote work control');
        const command = document.createElement('select');
        [['pause_work', 'Pause Amara work'], ['resume_work', 'Resume Amara work'], ['system_lockout', 'System lockout / ban'], ['system_unlock', 'Unlock system lockout']].forEach(([value, label]) => {
            const option = document.createElement('option'); option.value = value; option.textContent = label; command.append(option);
        });
        command.className = 'amara-command-select';
        const reason = document.createElement('input');
        reason.type = 'text'; reason.maxLength = 240; reason.placeholder = 'Lockout reason (required for ban)'; reason.className = 'amara-command-select';
        const send = document.createElement('button');
        send.type = 'button'; send.textContent = 'Queue command';
        send.addEventListener('click', () => current.root.dispatchEvent(new CustomEvent('amara-graph-command', { bubbles: true, detail: { command: command.value, reason: reason.value } })));
        content.append(command, reason, send);
        section(content, 'Recent timeline');
        const timeline = document.createElement('ol');
        timeline.className = 'amara-timeline';
        if (!data.timeline.length) {
            const empty = document.createElement('li');
            empty.textContent = 'No work evidence or activity reports received.';
            timeline.append(empty);
        }
        data.timeline.forEach(item => {
            const row = document.createElement('li');
            const title = document.createElement('strong');
            title.textContent = item.title;
            const detail = document.createElement('span');
            detail.textContent = `${item.detail} · ${time(item.at)} · ${item.source}`;
            row.append(title, detail);
            timeline.append(row);
        });
        content.append(timeline);
        panel.append(content);
    }

    function showDetail(state, id) {
        const node = state.nodeById.get(id);
        const panel = state.root.querySelector('#amara-graph-detail');
        panel.replaceChildren();
        if (!node) { panel.hidden = true; return; }
        panel.hidden = false;

        const kind = document.createElement('p');
        kind.className = 'kind';
        kind.textContent = node.kind === 'registry' ? 'Registry' : node.kind === 'shop' ? 'Shop node' : 'Amara worker';
        const title = document.createElement('h3');
        title.textContent = node.label;
        const list = document.createElement('dl');
        panel.append(kind, title, list);

        if (node.kind === 'device') {
            state.selectedDevice = node.deviceId;
            addField(list, 'Device ID', node.deviceKey);
            addField(list, 'Reporting', node.state);
            addField(list, 'Last heartbeat', node.lastSeen ? new Date(node.lastSeen).toLocaleString() : 'Never reported');
            addField(list, 'Last observed battery', node.battery == null ? 'Unknown' : `${node.battery}%`);
            addField(list, 'Last reported queue', node.pending);
            [['Open health history', 'health'], ['Open work evidence', 'events'], ['Open activity logs', 'activity']]
                .forEach(([label, tab]) => addButton(panel, label, () => {
                    state.root.dispatchEvent(new CustomEvent('amara-graph-device', {
                        bubbles: true, detail: { id: node.deviceId, tab },
                    }));
                }));
            const loading = document.createElement('p');
            loading.className = 'amara-intelligence-note';
            loading.textContent = 'Loading device intelligence…';
            panel.append(loading);
            state.root.dispatchEvent(new CustomEvent('amara-graph-inspect', { bubbles: true, detail: { id: node.deviceId } }));
        } else if (node.kind === 'shop') {
            state.selectedDevice = null;
            addField(list, 'Shop identity', node.scope === 'unverified' ? 'Unverified' : node.scope);
            addField(list, 'Association', node.verified ? 'Latest Terminal observation' : 'No verified shop observation');
            addButton(panel, 'Filter to this shop', () => {
                state.root.dispatchEvent(new CustomEvent('amara-graph-shop', { bubbles: true, detail: { scope: node.scope } }));
            });
        } else {
            state.selectedDevice = null;
            addField(list, 'Meaning', node.detail);
            addField(list, 'Workers in view', state.data.shownWorkers);
        }

        const neighbors = state.data.edges
            .filter(edge => edge.from === id || edge.to === id)
            .slice(0, 12)
            .map(edge => state.nodeById.get(edge.from === id ? edge.to : edge.from))
            .filter(Boolean);
        if (neighbors.length) {
            const heading = document.createElement('p');
            heading.className = 'kind';
            heading.textContent = `Connected nodes (${neighbors.length}${state.data.edges.length > 12 ? '+' : ''})`;
            panel.append(heading);
            neighbors.forEach(neighbor => addButton(panel, neighbor.label, () => focusNode(state, neighbor.id)));
        }
    }

    function focusNode(state, id) {
        state.network.selectNodes([id]);
        state.network.focus(id, { scale: 1.4, animation: { duration: 300, easingFunction: 'easeInOutQuad' } });
        showDetail(state, id);
    }

    function applySearch(state) {
        const query = state.root.querySelector('#amara-graph-search').value.trim().toLowerCase();
        const kind = state.root.querySelector('#amara-graph-kind').value;
        let matches = 0;
        state.data.nodes.forEach(node => {
            const matched = (!query || `${node.label} ${node.deviceKey || ''} ${node.scope || ''}`.toLowerCase().includes(query))
                && (kind === 'all' || node.kind === kind);
            if (matched) matches++;
            const color = matched ? colorFor(node) : '#334155';
            state.nodes.update({ id: node.id, color: { background: color, border: matched ? '#e2e8f0' : '#475569' },
                font: { color: matched ? '#e2e8f0' : '#64748b' } });
        });
        state.root.querySelector('#amara-graph-counts').textContent =
            `${state.data.shownWorkers}/${state.data.totalWorkers} workers · ${state.data.shopCount} shops · ${state.data.edges.length} links${query || kind !== 'all' ? ` · ${matches} matching nodes` : ''}`;
    }

    function bindControls(root) {
        root.addEventListener('input', event => {
            if (event.target.id === 'amara-graph-search' && current?.root === root) applySearch(current);
        });
        root.addEventListener('change', event => {
            if (event.target.id === 'amara-graph-kind' && current?.root === root) applySearch(current);
        });
        root.addEventListener('click', event => {
            const action = event.target.closest('[data-graph-action]')?.dataset.graphAction;
            if (!action || current?.root !== root) return;
            if (action === 'fit') current.network.fit({ animation: { duration: 250 } });
            if (action === 'zoom-in') current.network.moveTo({ scale: current.network.getScale() * 1.25 });
            if (action === 'zoom-out') current.network.moveTo({ scale: current.network.getScale() / 1.25 });
        });
        root.querySelector('#amara-graph-search').addEventListener('keydown', event => {
            if (event.key !== 'Enter' || current?.root !== root) return;
            event.preventDefault();
            const query = event.target.value.trim().toLowerCase();
            const match = current.data.nodes.find(node => `${node.label} ${node.deviceKey || ''}`.toLowerCase().includes(query));
            if (match) focusNode(current, match.id);
        });
    }

    function sync() {
        const root = document.getElementById('amara-brain-graph');
        if (!root) {
            current?.resizeObserver?.disconnect();
            if (current?.network) current.network.destroy();
            current = null;
            return;
        }
        if (!root.dataset.graphBound) { bindControls(root); root.dataset.graphBound = '1'; }
        const raw = root.querySelector('#amara-brain-graph-data')?.textContent || '';
        if (current?.root === root && current.raw === raw) return;
        current?.resizeObserver?.disconnect();
        if (current?.network) current.network.destroy();
        current = null;
        const detail = root.querySelector('#amara-graph-detail');
        detail.replaceChildren();
        detail.hidden = true;

        let data;
        try { data = JSON.parse(raw); } catch (_) { showMessage(root, 'Graph data could not be read. Use List to inspect devices.'); return; }
        if (!window.vis?.Network || !window.vis?.DataSet) {
            showMessage(root, 'Graph library is unavailable. Use List to inspect devices.');
            return;
        }
        if (!data.shownWorkers) {
            showMessage(root, 'No workers match these filters. Clear the search or choose another shop.');
            root.querySelector('#amara-graph-counts').textContent = '0 matching workers';
            return;
        }

        hideMessage(root);
        const nodes = new vis.DataSet(data.nodes.map(makeNode));
        const edges = new vis.DataSet(data.edges.map(makeEdge));
        const network = new vis.Network(root.querySelector('#amara-graph-canvas'), { nodes, edges }, {
            physics: { solver: 'forceAtlas2Based', forceAtlas2Based: { gravitationalConstant: -55,
                centralGravity: .008, springLength: 155, springConstant: .025, damping: .45 },
                stabilization: { iterations: 120, updateInterval: 30 } },
            interaction: { hover: true, navigationButtons: false, keyboard: true, tooltipDelay: 150 },
            edges: { smooth: { type: 'continuous' } },
            nodes: { borderWidthSelected: 3 },
        });
        current = { root, raw, data, nodes, edges, network, nodeById: new Map(data.nodes.map(node => [node.id, node])) };
        network.on('click', params => {
            if (params.nodes.length) showDetail(current, params.nodes[0]);
            else root.querySelector('#amara-graph-detail').hidden = true;
        });
        network.on('doubleClick', params => {
            if (params.nodes.length) focusNode(current, params.nodes[0]);
        });
        network.once('stabilizationIterationsDone', () => {
            network.setOptions({ physics: false });
            network.fit({ animation: { duration: 250 } });
        });
        applySearch(current);
        if (window.ResizeObserver) {
            current.resizeObserver = new ResizeObserver(() => network.redraw());
            current.resizeObserver.observe(root);
        }
    }

    function hookLivewire() {
        if (hooked || !window.Livewire?.hook) return;
        hooked = true;
        window.Livewire.hook('morph.updated', scheduleSync);
    }

    document.addEventListener('livewire:init', () => { hookLivewire(); scheduleSync(); });
    window.addEventListener('amara-intelligence-ready', event => {
        const payload = event.detail?.intelligence;
        if (payload) renderIntelligence(payload);
    });
    document.addEventListener('livewire:navigated', scheduleSync);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => { hookLivewire(); scheduleSync(); });
    } else { hookLivewire(); scheduleSync(); }
})();
