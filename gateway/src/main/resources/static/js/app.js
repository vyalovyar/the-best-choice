// app.js — исправленная версия
const state = {
    sessionId: null,
    participantId: null,
    sessionCode: null,
    participantIds: [],
    participants: [], // [{id, name}]
    isHost: false,
    votingStarted: false,

    currentMovieIndex: 0,
    movies: [],
    voted: false,
    votedDecision: null,
    participantVotes: {},

    stompClient: null,
    connected: false,

    sessionServiceUrl: window.BACKEND_SESSION_URL || `${window.location.origin}/api/sessions`,
    votingServiceUrl: window.BACKEND_VOTING_URL || `${window.location.origin}/api/voting`,
    wsUrl: window.BACKEND_WS_URL || getDefaultWsUrl(),
};

function getDefaultWsUrl() {
    // return http(s) url for SockJS — it will negotiate actual transport
    const proto = window.location.protocol; // 'https:' or 'http:'
    const host = window.location.host;
    return `${proto}//${host}/ws`;
}

function el(id) { return document.getElementById(id); }

function showToast(text) {
    const toast = el('toast');
    if (!toast) return;
    toast.textContent = text;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('visible'), 50);
    setTimeout(() => {
        toast.classList.remove('visible');
        setTimeout(() => toast.classList.add('hidden'), 350);
    }, 3000);
}

function generateParticipantId() {
    return 'p_' + Math.random().toString(36).slice(2, 11);
}

function fetchWithTimeout(url, options = {}, timeoutMs = 10000) {
    return Promise.race([
        fetch(url, options),
        new Promise((_, reject) =>
            setTimeout(() => reject(new Error(`Запрос истёк (${timeoutMs}мс)`)), timeoutMs)
        )
    ]);
}

/* UI tabs */
function switchTab(tabName) {
    const joinPanel = el('joinPanel');
    const createPanel = el('createPanel');
    const tabJoin = el('tabJoin');
    const tabCreate = el('tabCreate');

    if (tabName === 'join') {
        joinPanel.classList.remove('hidden');
        createPanel.classList.add('hidden');
        tabJoin.classList.add('active');
        tabCreate.classList.remove('active');
    } else {
        joinPanel.classList.add('hidden');
        createPanel.classList.remove('hidden');
        tabJoin.classList.remove('active');
        tabCreate.classList.add('active');
    }

    ['joinError','createError'].forEach(id => {
        const node = el(id);
        if (node) node.classList.add('hidden');
    });
}

/* Create / Join */
async function createSession() {
    try {
        const btn = el('createBtn');
        if (btn) { btn.disabled = true; btn.textContent = 'Создание...'; }

        const response = await fetchWithTimeout(state.sessionServiceUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
        }, 10000);

        if (!response.ok) throw new Error(`Ошибка создания сессии (${response.status})`);

        const data = await response.json();
        state.sessionId = data.sessionId || data.id || null;
        state.sessionCode = data.code || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');
        state.currentMovieIndex = parseInt(data.currentMovieIndex || 0, 10) || 0;
        state.isHost = true;
        state.participantId = generateParticipantId();

        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);

        state.participants = [{ id: state.participantId, name: 'Вы (хост)' }];

        showWaitingScreen();
        connectWebSocket();
    } catch (err) {
        console.error('createSession err', err);
        const node = el('createError');
        if (node) { node.textContent = err.message || 'Не удалось создать сессию'; node.classList.remove('hidden'); }
        const btn = el('createBtn'); if (btn) { btn.disabled = false; btn.textContent = 'Создать сессию'; }
    }
}

async function joinSession() {
    try {
        const link = el('sessionLink').value.trim();
        if (!link) throw new Error('Введите ссылку сессии');

        let sessionId;
        try {
            const u = new URL(link);
            sessionId = u.searchParams.get('session') || u.searchParams.get('id') || null;
            if (!sessionId) {
                const seg = u.pathname.split('/').filter(Boolean).pop();
                sessionId = seg;
            }
        } catch (e) {
            throw new Error('Неверный формат ссылки');
        }
        if (!sessionId) throw new Error('Не найден sessionId в ссылке');

        state.sessionId = sessionId;
        state.participantId = generateParticipantId();
        state.isHost = false;

        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);

        await fetchSessionInfo(state.sessionId);

        showWaitingScreen();
        connectWebSocket();
    } catch (err) {
        console.error('joinSession err', err);
        const node = el('joinError');
        if (node) { node.textContent = err.message || 'Не удалось присоединиться'; node.classList.remove('hidden'); }
    }
}

async function joinSessionDirect() {
    try {
        state.participantId = generateParticipantId();
        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);
        state.isHost = false;

        await fetchSessionInfo(state.sessionId);

        showWaitingScreen();
        connectWebSocket();
    } catch (err) {
        console.error('joinSessionDirect err', err);
        const node = el('joinError');
        if (node) { node.textContent = 'Не удалось присоединиться к сессии'; node.classList.remove('hidden'); }
        showWelcomeScreen();
    }
}

async function fetchSessionInfo(sessionId) {
    try {
        const resp = await fetch(`${state.sessionServiceUrl}/${sessionId}`);
        if (!resp.ok) {
            state.sessionCode = sessionId.substring(0,6).toUpperCase();
            return;
        }
        const data = await resp.json();
        state.sessionCode = data.code || state.sessionCode || sessionId.substring(0,6).toUpperCase();
        if (Array.isArray(data.participants)) {
            // ensure participants are {id, name}
            state.participants = data.participants.map(p => ({ id: p.id, name: p.name || '' }));
        }
        state.votingStarted = !!data.votingStarted;
    } catch (err) {
        console.warn('fetchSessionInfo warning', err);
        state.sessionCode = state.sessionCode || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');
    }
}
function connectWebSocket() {
    if (!state.sessionId) {
        console.warn('connectWebSocket: no sessionId');
        return;
    }

    console.log('Connecting to WebSocket:', state.wsUrl, 'sessionId:', state.sessionId);
    try {
        const socket = new SockJS(state.wsUrl);
        state.stompClient = Stomp.over(socket);
        // silence stomp logs safely
        state.stompClient.debug = () => {};

        state.stompClient.connect({}, (frame) => {
            console.log('WS connected', frame);
            state.connected = true;

            // participants
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/participants`, (msg) => {
                try {
                    // server may send either array or object
                    let payload = JSON.parse(msg.body);
                    // normalize: if body is array, wrap in object { participants: [...] }
                    if (Array.isArray(payload)) {
                        handleParticipantsUpdate({ participants: payload });
                    } else {
                        handleParticipantsUpdate(payload);
                    }
                } catch (e) { console.warn('participants parse err', e); }
            });

            // start
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/start`, (msg) => {
                try {
                    const payload = msg.body ? JSON.parse(msg.body) : {};
                    handleStartMessage(payload);
                } catch (e) { console.warn('start parse err', e); handleStartMessage({}); }
            });

            // votes
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/votes`, (msg) => {
                try {
                    handleVoteUpdate(msg);
                } catch (e) { console.warn('vote parse err', e); }
            });

            // match
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/match`, (msg) => {
                try {
                    handleMatchMessage(msg);
                } catch (e) { console.warn('match parse err', e); }
            });

            // notify backend about presence (optional)
            try {
                state.stompClient.send('/app/participant-join', {}, JSON.stringify({
                    sessionId: state.sessionId,
                    participantId: state.participantId,
                    name: null
                }));
            } catch (e) { /* not critical */ }

        }, (err) => {
            console.error('WS connect error', err);
            if (err && err.message && err.message.includes('301')) {
                console.error('Возможен редирект HTTP→HTTPS. Проверь BACKEND_WS_URL (используй wss:// для production).');
            }
            state.connected = false;
            setTimeout(() => {
                if (state.sessionId) connectWebSocket();
            }, 3000);
        });
    } catch (err) {
        console.error('connectWebSocket exception', err);
    }
}

/* Handlers */
function handleParticipantsUpdate(payload) {
    // accepted formats:
    // { participants: [...] , newParticipantId: 'p_x' }
    // or { participant: {...} }
    // or plain array (handled in subscribe)
    if (!payload) return;

    let list = [];
    if (Array.isArray(payload)) {
        list = payload;
    } else if (Array.isArray(payload.participants)) {
        list = payload.participants;
    } else if (payload.participant) {
        list = (state.participants || []).concat([payload.participant]);
    } else {
        // unknown shape
        console.warn('Unknown participants payload shape', payload);
        return;
    }

    // normalize: ensure each element is {id, name}
    const map = new Map();
    // include existing to preserve any names we had
    (state.participants || []).forEach(p => {
        if (p && p.id) map.set(p.id, { id: p.id, name: p.name || '' });
    });

    list.forEach(p => {
        if (!p) return;
        const id = p.id || p.participantId || String(p);
        const name = p.name || p.displayName || '';
        map.set(id, { id, name });
    });

    // ensure current participant is present
    if (state.participantId && !map.has(state.participantId)) {
        map.set(state.participantId, { id: state.participantId, name: state.isHost ? 'Вы (хост)' : 'Вы' });
    }

    state.participants = Array.from(map.values());

    renderParticipants();

    if (payload.newParticipantId && payload.newParticipantId !== state.participantId) {
        showToast('Новый участник подключился');
    }
}

function handleStartMessage(payload) {
    state.votingStarted = true;
    showToast('Выбор начат');
    initVoting().catch(err => {
        console.error('initVoting after start error', err);
        showToast('Не удалось начать голосование');
    });
}

function handleVoteUpdate(message) {
    let vote;
    try { vote = JSON.parse(message.body); } catch (e) { console.warn('parse vote', e); return; }
    if (!vote || !vote.participantId) return;

    if (!state.participants.some(p => p.id === vote.participantId)) {
        state.participants.push({ id: vote.participantId, name: '' });
        renderParticipants();
    }

    state.participantVotes[vote.participantId] = vote.decision;
    updateParticipantStatusById(vote.participantId, 'voted');

    const required = state.participants.length || Math.max(2, Object.keys(state.participantVotes).length);
    if (Object.keys(state.participantVotes).length >= required) {
        const votes = Object.values(state.participantVotes);
        const allLike = votes.length > 0 && votes.every(v => v === 'LIKE');

        if (allLike) {
            const currentMovie = state.movies[state.currentMovieIndex];
            const match = {
                movieTitle: currentMovie ? currentMovie.title : 'Фильм',
                posterPath: currentMovie ? currentMovie.posterPath : null
            };
            setTimeout(() => showMatchScreen(match), 800);
        } else {
            setTimeout(() => moveToNextMovie(), 800);
        }
    }
}

function handleMatchMessage(message) {
    let match;
    try { match = JSON.parse(message.body); } catch (e) { console.warn('parse match', e); return; }
    showMatchScreen(match);
}

/* Rendering */
function renderParticipants() {
    const list = el('participantsList');
    if (!list) return;
    list.innerHTML = '';

    state.participants.forEach((p, idx) => {
        const item = document.createElement('div');
        item.className = 'participant-item';

        const avatar = document.createElement('div');
        avatar.className = 'avatar';
        const nameForAvatar = (p.name && p.name !== 'Вы') ? p.name : (p.id || 'U');
        avatar.textContent = (String(nameForAvatar).slice(0,2)).toUpperCase();

        const meta = document.createElement('div');
        meta.className = 'p-meta';
        const nameEl = document.createElement('div');
        nameEl.className = 'p-name';
        nameEl.textContent = (p.id === state.participantId) ? 'Вы' : (p.name || `Участник ${idx+1}`);

        const sub = document.createElement('div');
        sub.className = 'p-sub';
        sub.textContent = (p.id === state.participantId && state.isHost) ? 'Организатор (хост)' : 'Ожидает начала';

        meta.appendChild(nameEl);
        meta.appendChild(sub);

        const status = document.createElement('div');
        status.className = 'p-status p-status-indicator wait';
        status.id = `status-${p.id}`;
        status.textContent = 'Ожидает';

        item.appendChild(avatar);
        item.appendChild(meta);
        item.appendChild(status);
        list.appendChild(item);
    });

    const startBtn = el('startBtn');
    if (startBtn) {
        if (state.isHost) startBtn.classList.remove('hidden'); else startBtn.classList.add('hidden');
        // enable start button only when >=2 participants
        startBtn.disabled = !(state.isHost && state.participants.length >= 2);
    }

    const codeText = el('waitingCodeText');
    if (codeText) codeText.textContent = state.sessionCode || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');

    const inviteInput = el('inviteLink');
    if (inviteInput) inviteInput.value = `${window.location.origin}/?session=${state.sessionId}`;
}

function updateParticipantStatusById(participantId, status) {
    const node = el(`status-${participantId}`);
    if (!node) return;
    if (status === 'voted') {
        node.textContent = 'Проголосовал';
        node.classList.remove('wait');
        node.classList.add('voted');
    } else {
        node.textContent = 'Ожидает';
        node.classList.remove('voted');
        node.classList.add('wait');
    }
    renderStatusRow();
}

function renderStatusRow() {
    const row = el('statusRow');
    if (!row) return;
    row.innerHTML = '';
    state.participants.forEach(p => {
        const div = document.createElement('div');
        div.className = 'status-item';
        const voted = state.participantVotes[p.id];
        div.textContent = (p.id === state.participantId) ? 'Вы: ' + (voted ? 'Проголосовал' : 'Ожидает') : ((voted ? 'Проголосовал' : 'Ожидает') + ` (${p.id === state.participantId ? 'Вы' : 'Участник'})`);
        row.appendChild(div);
    });
}

/* Lobby actions */
function copyToClipboard() {
    const link = el('inviteLink').value;
    const btn = el('copyInviteBtn');
    if (!navigator.clipboard) {
        const tmp = document.createElement('textarea');
        tmp.value = link;
        document.body.appendChild(tmp);
        tmp.select();
        try { document.execCommand('copy'); showToast('Ссылка скопирована'); } catch (e) { showToast('Не удалось скопировать'); }
        tmp.remove();
        return;
    }
    navigator.clipboard.writeText(link).then(() => {
        if (btn) {
            const original = btn.textContent;
            btn.textContent = 'Скопировано';
            setTimeout(() => btn.textContent = original, 1500);
        } else showToast('Скопировано');
    }).catch(() => showToast('Не удалось скопировать'));
}

function pasteFromClipboard() {
    if (!navigator.clipboard) { showToast('Clipboard API недоступен'); return; }
    navigator.clipboard.readText().then(t => { el('sessionLink').value = t; });
}

function startVoting() {
    if (!state.isHost) return;
    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/start-voting', {}, JSON.stringify({ sessionId: state.sessionId, by: state.participantId }));
        } catch (e) {
            console.warn('startVoting send failed, fallback', e);
            handleStartMessage({});
        }
    } else {
        handleStartMessage({});
    }
}
async function initVoting() {
    try {
        const resp = await fetch(`${state.votingServiceUrl}/movies`);
        if (!resp.ok) throw new Error(`Ошибка загрузки фильмов (${resp.status})`);
        state.movies = await resp.json();
        if (!Array.isArray(state.movies) || state.movies.length === 0) throw new Error('Нет доступных фильмов');

        state.currentMovieIndex = state.currentMovieIndex || 0;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {};

        loadCurrentMovie();
        showVotingScreen();

        const codeNode = el('votingSessionCode'); if (codeNode) codeNode.textContent = state.sessionCode || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');
        const notice = el('votingNotice'); if (notice) notice.textContent = 'Голосование началось';
        el('yesBtn').disabled = false; el('noBtn').disabled = false;
        renderStatusRow();
    } catch (err) {
        console.error('initVoting err', err);
        const node = el('joinError'); if (node) { node.textContent = err.message || 'Ошибка инициализации голосования'; node.classList.remove('hidden'); }
    }
}

function vote(decision) {
    if (state.voted || !state.votingStarted) return;
    const movie = state.movies[state.currentMovieIndex];
    if (!movie) return;

    state.voted = true;
    state.votedDecision = decision;
    el('yesBtn').disabled = true; el('noBtn').disabled = true;
    updateParticipantStatusById(state.participantId, 'voted');

    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/vote', {}, JSON.stringify({
                sessionId: state.sessionId,
                participantId: state.participantId,
                movieId: movie.id,
                decision: decision
            }));
        } catch (e) {
            console.warn('vote send failed', e);
        }
    } else {
        state.participantVotes[state.participantId] = decision;
        renderStatusRow();
        const required = state.participants.length || 2;
        if (Object.keys(state.participantVotes).length >= required) {
            const votes = Object.values(state.participantVotes);
            if (votes.every(v => v === 'LIKE')) {
                showMatchScreen({ movieTitle: movie.title, posterPath: movie.posterPath });
            } else {
                moveToNextMovie();
            }
        }
    }
}
function moveToNextMovie() {
    state.participantVotes = {};
    state.voted = false;
    state.votedDecision = null;
    state.participants.forEach(p => updateParticipantStatusById(p.id, 'wait'));
    state.currentMovieIndex = (state.currentMovieIndex + 1) % state.movies.length;
    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/update-movie-index', {}, JSON.stringify({ sessionId: state.sessionId, movieIndex: state.currentMovieIndex }));
        } catch (e) {}
    }
    loadCurrentMovie();
}

function loadCurrentMovie() {
    const movie = state.movies[state.currentMovieIndex];
    if (!movie) {
        el('movieTitle').textContent = 'Фильм не найден';
        el('moviePoster').src = '';
        el('movieCounter').textContent = `Фильм ${state.currentMovieIndex+1} из ${state.movies.length || 0}`;
        return;
    }
    el('movieTitle').textContent = movie.title || 'Без названия';
    el('movieMeta').textContent = movie.year ? `${movie.year} • ${movie.genre || ''}` : (movie.overview || '');
    el('movieCounter').textContent = `Фильм ${state.currentMovieIndex+1} из ${state.movies.length}`;
    const imageUrl = movie.posterPath ? `https://image.tmdb.org/t/p/w500${movie.posterPath}` : 'https://via.placeholder.com/340x510?text=No+Image';
    el('moviePoster').src = imageUrl; el('moviePoster').alt = movie.title || 'Poster';
    el('yesBtn').classList.remove('voted'); el('noBtn').classList.remove('voted');
    el('yesBtn').disabled = false; el('noBtn').disabled = false;
    renderStatusRow();
}

/* Match */
function showMatchScreen(match) {
    hideAllScreens();
    el('matchTitle').textContent = 'Совпадение';
    el('matchMovieTitle').textContent = match.movieTitle || 'Фильм';
    const url = match.posterPath ? `https://image.tmdb.org/t/p/w500${match.posterPath}` : 'https://via.placeholder.com/260x390?text=No+Image';
    el('matchPoster').src = url;
    el('matchScreen').classList.add('active');
    playConfetti();
}

function nextMatch() {
    hideAndResetVoting();
    showVotingScreen();
}

/* Navigation */
function hideAllScreens() { document.querySelectorAll('.screen').forEach(s => s.classList.remove('active')); }
function showWelcomeScreen() { hideAllScreens(); el('welcomeScreen').classList.add('active'); }
function showWaitingScreen() {
    hideAllScreens();
    const invite = el('inviteLink'); if (invite) invite.value = `${window.location.origin}/?session=${state.sessionId}`;
    const code = el('waitingCodeText'); if (code) code.textContent = state.sessionCode || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');
    el('waitingScreen').classList.add('active');
    if (!state.participants || state.participants.length === 0) {
        state.participants = [{ id: state.participantId, name: state.isHost ? 'Вы (хост)' : 'Вы' }];
    }
    renderParticipants();
}
function showVotingScreen() { hideAllScreens(); state.votingStarted = true; el('votingScreen').classList.add('active'); }
function hideAndResetVoting() {
    state.voted = false; state.votedDecision = null; state.participantVotes = {};
    state.participants.forEach(p => updateParticipantStatusById(p.id, 'wait'));
    if (el('yesBtn')) el('yesBtn').disabled = false; if (el('noBtn')) el('noBtn').disabled = false;
}

/* Exit */
function exitSession() {
    if (state.stompClient && state.connected) {
        try { state.stompClient.disconnect(); } catch (e) {}
        state.connected = false;
    }
    state.sessionId = null; state.participantId = null; state.participants = []; state.votingStarted = false;
    localStorage.removeItem('participantId'); localStorage.removeItem('sessionId');
    showWelcomeScreen();
}
function goBackToWelcome() { exitSession(); }

/* Confetti */
function playConfetti() {
    const count = 36;
    for (let i = 0; i < count; i++) {
        const c = document.createElement('div');
        c.style.position = 'fixed'; c.style.top = '-10px'; c.style.left = Math.random() * 100 + '%';
        c.style.width = (6 + Math.random() * 10) + 'px'; c.style.height = (10 + Math.random() * 16) + 'px';
        c.style.background = ['#6366f1','#4f46e5','#10b981','#06b6d4','#f97316'][Math.floor(Math.random()*5)];
        c.style.opacity = 0.95; c.style.borderRadius = '2px'; c.style.transform = `rotate(${Math.random()*360}deg)`;
        c.style.zIndex = 9999; c.style.pointerEvents = 'none';
        c.style.transition = 'transform 2.4s cubic-bezier(.2,.9,.2,1), top 2.4s ease, opacity 2.4s ease';
        document.body.appendChild(c);
        const destX = (Math.random()*60 - 30);
        const destY = 100 + Math.random()*40;
        setTimeout(() => {
            c.style.top = destY + 'vh';
            c.style.transform = `translateX(${destX}vw) rotate(${Math.random()*720}deg)`;
            c.style.opacity = 0;
        }, 50);
        setTimeout(() => c.remove(), 2600);
    }
}
document.addEventListener('DOMContentLoaded', () => {
    const env = el('envInfo'); if (env) env.textContent = `WS: ${state.wsUrl}, Voting API: ${state.votingServiceUrl}`;
    const savedPid = localStorage.getItem('participantId'); const savedSid = localStorage.getItem('sessionId');
    if (savedPid && savedSid) {
        state.participantId = savedPid; state.sessionId = savedSid;
        fetchSessionInfo(state.sessionId).catch(()=>{});
    }
    checkUrlParams();
    switchTab('join');
});

/* URL params */
function checkUrlParams() {
    const params = new URLSearchParams(window.location.search);
    const sessionId = params.get('session');
    if (sessionId) { state.sessionId = sessionId; joinSessionDirect(); }
}

/* Expose */
window.switchTab = switchTab;
window.createSession = createSession;
window.joinSession = joinSession;
window.startVoting = startVoting;
window.copyToClipboard = copyToClipboard;
window.pasteFromClipboard = pasteFromClipboard;
window.exitSession = exitSession;
window.goBackToWelcome = goBackToWelcome;
window.vote = vote;
window.nextMatch = nextMatch;
window.joinSessionDirect = joinSessionDirect;