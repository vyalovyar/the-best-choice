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

    // backend urls (можно задавать из HTML как window.BACKEND_*)
    sessionServiceUrl: window.BACKEND_SESSION_URL || `${window.location.origin}/api/sessions`,
    votingServiceUrl: window.BACKEND_VOTING_URL || `${window.location.origin}/api/voting`,
    wsUrl: window.BACKEND_WS_URL || getDefaultWsUrl(),
};

/* ====== HELPERS ====== */
function getDefaultWsUrl() {
    const protocol = window.location.protocol === 'https:' ? 'https:' : 'http:';
    const host = window.location.host;
    return `${protocol}//${host}/ws`;
}

function el(id) { return document.getElementById(id); }

function showToast(text) {
    const toast = el('toast');
    toast.textContent = text;
    toast.classList.remove('hidden');
    // trigger visible
    setTimeout(() => toast.classList.add('visible'), 50);
    // hide
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

    // clear errors
    ['joinError','createError'].forEach(id => {
        const node = el(id);
        if (node) node.classList.add('hidden');
    });
}
async function createSession() {
    try {
        const btn = el('createBtn');
        btn.disabled = true;
        btn.textContent = 'Создание...';

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

        // persist
        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);
        showWaitingScreen();
        connectWebSocket();
        state.participants = [{ id: state.participantId, name: 'Вы (хост)' }];
        renderParticipants();

        btn.textContent = 'Создать сессию';
        btn.disabled = false;
    } catch (err) {
        console.error('createSession err', err);
        const node = el('createError');
        node.textContent = err.message || 'Не удалось создать сессию';
        node.classList.remove('hidden');
        el('createBtn').disabled = false;
        el('createBtn').textContent = 'Создать сессию';
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
                // try parse last path segment
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

        // try to fetch metadata (code, participants, votingStarted) if backend supports
        await fetchSessionInfo(state.sessionId);

        // go to waiting screen and connect
        showWaitingScreen();
        connectWebSocket();
    } catch (err) {
        console.error('joinSession err', err);
        const node = el('joinError');
        node.textContent = err.message || 'Не удалось присоединиться';
        node.classList.remove('hidden');
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
        el('joinError').textContent = 'Не удалось присоединиться к сессии';
        el('joinError').classList.remove('hidden');
        showWelcomeScreen();
    }
}

async function fetchSessionInfo(sessionId) {
    try {
        const resp = await fetch(`${state.sessionServiceUrl}/${sessionId}`);
        if (!resp.ok) {
            // fallback to using first 6 chars as code
            state.sessionCode = sessionId.substring(0,6).toUpperCase();
            return;
        }
        const data = await resp.json();
        state.sessionCode = data.code || state.sessionCode || sessionId.substring(0,6).toUpperCase();
        // if backend returns participants or votingStarted, use them
        if (Array.isArray(data.participants)) {
            state.participants = data.participants;
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

    console.log(' Connecting to WebSocket:', state.wsUrl);
    console.log(' Session ID:', state.sessionId);

    try {
        const socket = new SockJS(state.wsUrl);
        state.stompClient = Stomp.over(socket);
        state.stompClient.debug = null; // ✅ Отключаем шумные логи SockJS

        state.stompClient.connect({}, (frame) => {
            console.log('✅ WS connected:', frame);
            state.connected = true;

            // subscribe to participants updates (backend should broadcast participants list)
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/participants`, (msg) => {
                try {
                    const payload = JSON.parse(msg.body);
                    handleParticipantsUpdate(payload);
                } catch (e) { console.warn(e); }
            });

            // subscribe to start trigger
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/start`, (msg) => {
                try {
                    const payload = JSON.parse(msg.body || '{}');
                    handleStartMessage(payload);
                } catch (e) { console.warn(e); }
            });

            // subscribe to votes updates
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/votes`, (msg) => {
                try {
                    handleVoteUpdate(msg);
                } catch (e) { console.warn(e); }
            });

            // subscribe to match (server-driven)
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/match`, (msg) => {
                try {
                    handleMatchMessage(msg);
                } catch (e) { console.warn(e); }
            });

            // Optionally notify backend about presence
            try {
                state.stompClient.send('/app/participant-join', {}, JSON.stringify({
                    sessionId: state.sessionId,
                    participantId: state.participantId,
                }));
            } catch (e) {
                // not critical
            }

        }, (err) => {
            console.error('WS connect error', err);
            if (err && err.message && err.message.includes('301')) {
                console.error(' Возможно, проблема с редиректом HTTP→HTTPS. Проверь, что Gateway правильно проксирует /ws');
            }
            state.connected = false;
            setTimeout(() => {
                if (state.sessionId) {
                    console.log('Повторное подключение через 3 сек...');
                    connectWebSocket();
                }
            }, 3000);
        });
    } catch (err) {
        console.error('connectWebSocket exception', err);
    }
}
function handleParticipantsUpdate(payload) {
    // payload expected: { participants: [{id, name}], newParticipantId: 'p_xxx' }
    if (!payload) return;
    if (Array.isArray(payload.participants)) {
        state.participants = payload.participants.slice();
    } else if (payload.participant) {
        // single participant object
        state.participants = state.participants.filter(Boolean);
        state.participants.push(payload.participant);
    }

    // ensure current participant present
    if (!state.participants.some(p => p.id === state.participantId)) {
        state.participants.push({ id: state.participantId, name: 'Вы' });
    }

    renderParticipants();

    if (payload.newParticipantId && payload.newParticipantId !== state.participantId) {
        showToast('Новый участник подключился');
    }
}
function handleStartMessage(payload) {
    state.votingStarted = true;
    showToast('Выбор начат');
    // initialize voting flow
    initVoting().catch(err => {
        console.error('initVoting after start error', err);
        showToast('Не удалось начать голосование');
    });
}

function handleVoteUpdate(message) {
    const vote = JSON.parse(message.body);
    // vote expected: { sessionId, participantId, movieId, decision }
    if (!vote || !vote.participantId) return;
    if (!state.participants.some(p => p.id === vote.participantId)) {
        state.participants.push({ id: vote.participantId, name: 'Участник' });
        renderParticipants();
    }

    // store vote for current movie
    state.participantVotes[vote.participantId] = vote.decision;
    updateParticipantStatusById(vote.participantId, 'voted');
    const required = state.participants.length || Math.max(2, Object.keys(state.participantVotes).length);
    if (Object.keys(state.participantVotes).length >= required) {
        // determine if all voted LIKE for match
        const votes = Object.values(state.participantVotes);
        const allLike = votes.length > 0 && votes.every(v => v === 'LIKE');

        if (allLike) {
            // if server sends match topic it will be handled; also we show local match
            const currentMovie = state.movies[state.currentMovieIndex];
            const match = {
                movieTitle: currentMovie ? currentMovie.title : 'Фильм',
                posterPath: currentMovie ? currentMovie.posterPath : null
            };
            // small delay to match UX
            setTimeout(() => {
                showMatchScreen(match);
            }, 800);
        } else {
            // move to next movie
            setTimeout(() => {
                moveToNextMovie();
            }, 800);
        }
    }
}

function handleMatchMessage(message) {
    // server-provided match payload
    const match = JSON.parse(message.body);
    // map to our expected format
    showMatchScreen(match);
}
function renderParticipants() {
    const list = el('participantsList');
    if (!list) return;
    list.innerHTML = '';

    state.participants.forEach((p, idx) => {
        const item = document.createElement('div');
        item.className = 'participant-item';

        const avatar = document.createElement('div');
        avatar.className = 'avatar';
        // avatar text: first two chars of id or name
        const nameForAvatar = (p.name && p.name !== 'Вы') ? p.name : (p.id || 'U');
        avatar.textContent = (nameForAvatar.slice(0,2)).toUpperCase();

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

    // show start button only for host
    const startBtn = el('startBtn');
    if (state.isHost) startBtn.classList.remove('hidden');
    else startBtn.classList.add('hidden');

    // fill invite link and code
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

/* ====== LOBBY actions ====== */
function copyToClipboard() {
    const link = el('inviteLink').value;
    const btn = el('copyInviteBtn');
    if (!navigator.clipboard) {
        // fallback
        const tmp = document.createElement('textarea');
        tmp.value = link;
        document.body.appendChild(tmp);
        tmp.select();
        try { document.execCommand('copy'); showToast('Ссылка скопирована'); } catch (e) { showToast('Не удалось скопировать'); }
        tmp.remove();
        return;
    }
    navigator.clipboard.writeText(link).then(() => {
        const original = btn.textContent;
        btn.textContent = 'Скопировано';
        setTimeout(() => btn.textContent = original, 1500);
    }).catch(() => showToast('Не удалось скопировать'));
}

function pasteFromClipboard() {
    if (!navigator.clipboard) {
        showToast('Clipboard API недоступен');
        return;
    }
    navigator.clipboard.readText().then(t => {
        el('sessionLink').value = t;
    });
}

function startVoting() {
    if (!state.isHost) return;
    // notify server to broadcast start (server should publish to /topic/session/{id}/start)
    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/start-voting', {}, JSON.stringify({ sessionId: state.sessionId }));
        } catch (e) {
            console.warn('startVoting send failed, falling back to local start', e);
            // fallback to local start
            handleStartMessage({});
        }
    } else {
        // no ws: start locally
        handleStartMessage({});
    }
}
async function initVoting() {
    try {
        // load movies
        const resp = await fetch(`${state.votingServiceUrl}/movies`);
        if (!resp.ok) throw new Error(`Ошибка загрузки фильмов (${resp.status})`);
        state.movies = await resp.json();

        if (!Array.isArray(state.movies) || state.movies.length === 0) {
            throw new Error('Нет доступных фильмов');
        }

        // reset voting state
        state.currentMovieIndex = state.currentMovieIndex || 0;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {};

        // show voting screen
        loadCurrentMovie();
        showVotingScreen();

        // update UI
        el('votingSessionCode').textContent = state.sessionCode || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');
        el('votingNotice').textContent = 'Голосование началось';
        // enable buttons
        el('yesBtn').disabled = false;
        el('noBtn').disabled = false;

        // render statuses
        renderStatusRow();
    } catch (err) {
        console.error('initVoting err', err);
        el('joinError').textContent = err.message || 'Ошибка инициализации голосования';
        el('joinError').classList.remove('hidden');
    }
}

function vote(decision) {
    if (state.voted || !state.votingStarted) return;

    const movie = state.movies[state.currentMovieIndex];
    if (!movie) return;

    state.voted = true;
    state.votedDecision = decision;

    // disable buttons
    el('yesBtn').disabled = true;
    el('noBtn').disabled = true;

    // local visual: mark own status
    updateParticipantStatusById(state.participantId, 'voted');

    // send via websocket
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
        // fallback: just store locally and check if all voted
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
    // reset votes
    state.participantVotes = {};
    state.voted = false;
    state.votedDecision = null;
    state.participants.forEach(p => updateParticipantStatusById(p.id, 'wait'));
    state.currentMovieIndex = (state.currentMovieIndex + 1) % state.movies.length;
    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/update-movie-index', {}, JSON.stringify({
                sessionId: state.sessionId,
                movieIndex: state.currentMovieIndex
            }));
        } catch (e) { /* ignore */ }
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
    el('moviePoster').src = imageUrl;
    el('moviePoster').alt = movie.title || 'Poster';

    // reset UI vote buttons
    el('yesBtn').classList.remove('voted');
    el('noBtn').classList.remove('voted');
    el('yesBtn').disabled = false;
    el('noBtn').disabled = false;

    // reset status row
    renderStatusRow();
}

/* ====== MATCH screen ====== */
function showMatchScreen(match) {
    // payload: { movieTitle, posterPath }
    hideAllScreens();
    el('matchTitle').textContent = 'Совпадение';
    el('matchMovieTitle').textContent = match.movieTitle || 'Фильм';
    const url = match.posterPath ? `https://image.tmdb.org/t/p/w500${match.posterPath}` : 'https://via.placeholder.com/260x390?text=No+Image';
    el('matchPoster').src = url;
    el('matchScreen').classList.add('active');

    // small confetti effect (CSS rectangles)
    playConfetti();
}

function nextMatch() {
    // reset and go back to voting
    hideAndResetVoting();
    showVotingScreen();
}

/* ====== NAVIGATION / screens ====== */
function hideAllScreens() {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
}
function showWelcomeScreen() {
    hideAllScreens();
    el('welcomeScreen').classList.add('active');
}
function showWaitingScreen() {
    hideAllScreens();
    // fill link + code
    el('inviteLink').value = `${window.location.origin}/?session=${state.sessionId}`;
    el('waitingCodeText').textContent = state.sessionCode || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');
    el('waitingScreen').classList.add('active');

    // render participants
    if (!state.participants || state.participants.length === 0) {
        state.participants = [{ id: state.participantId, name: state.isHost ? 'Вы (хост)' : 'Вы' }];
    }
    renderParticipants();
}
function showVotingScreen() {
    hideAllScreens();
    state.votingStarted = true;
    el('votingScreen').classList.add('active');
}
function hideAndResetVoting() {
    state.voted = false;
    state.votedDecision = null;
    state.participantVotes = {};
    state.participants.forEach(p => updateParticipantStatusById(p.id, 'wait'));
    el('yesBtn').disabled = false;
    el('noBtn').disabled = false;
}

/* ====== SESSION exit / back ====== */
function exitSession() {
    // disconnect websocket
    if (state.stompClient && state.connected) {
        try {
            state.stompClient.disconnect();
        } catch (e) { /* ignore */ }
        state.connected = false;
    }
    state.sessionId = null;
    state.participantId = null;
    state.participants = [];
    state.votingStarted = false;
    localStorage.removeItem('participantId');
    localStorage.removeItem('sessionId');

    showWelcomeScreen();
}

function goBackToWelcome() {
    // disconnect, but keep local session? For safety we clear
    exitSession();
}
function playConfetti() {
    const count = 36;
    for (let i = 0; i < count; i++) {
        const c = document.createElement('div');
        c.style.position = 'fixed';
        c.style.top = '-10px';
        c.style.left = Math.random() * 100 + '%';
        c.style.width = (6 + Math.random() * 10) + 'px';
        c.style.height = (10 + Math.random() * 16) + 'px';
        c.style.background = ['#6366f1','#4f46e5','#10b981','#06b6d4','#f97316'][Math.floor(Math.random()*5)];
        c.style.opacity = 0.95;
        c.style.borderRadius = '2px';
        c.style.transform = `rotate(${Math.random()*360}deg)`;
        c.style.zIndex = 9999;
        c.style.pointerEvents = 'none';
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
    // wire up UI initial text
    const env = el('envInfo');
    if (env) {
        env.textContent = `WebSocket: ${state.wsUrl}, Voting API: ${state.votingServiceUrl}`;
    }
    const savedPid = localStorage.getItem('participantId');
    const savedSid = localStorage.getItem('sessionId');
    if (savedPid && savedSid) {
        // try direct join
        state.participantId = savedPid;
        state.sessionId = savedSid;
        // fetch session code if possible, then go to waiting
        fetchSessionInfo(state.sessionId).then(() => {
        }).catch(()=>{});
    }

    // attach url-check for direct joins
    checkUrlParams();

    // small: if user toggles tab buttons (from html), ensure join is default
    switchTab('join');
});

/* ====== URL params check (auto join) ====== */
function checkUrlParams() {
    const params = new URLSearchParams(window.location.search);
    const sessionId = params.get('session');
    if (sessionId) {
        state.sessionId = sessionId;
        joinSessionDirect();
    }
}
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