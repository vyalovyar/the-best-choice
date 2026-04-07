const state = {
    sessionId: null,
    participantId: null,
    participantName: null,
    sessionCode: null,
    isHost: false,
    votingStarted: false,
    currentMovieIndex: 0,
    movies: [],
    voted: false,
    votedDecision: null,
    participants: [],
    participantVotes: {},
    stompClient: null,
    connected: false,
    isCreating: false,
    roundResolved: false,
    sessionServiceUrl: window.BACKEND_SESSION_URL || `${window.location.origin}/api/sessions`,
    votingServiceUrl: window.BACKEND_VOTING_URL || `${window.location.origin}/api/voting`,
    wsUrl: window.BACKEND_WS_URL || getDefaultWsUrl(),
    warmupPromise: null,
    participantsPollId: null,
};

function getDefaultWsUrl() {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${window.location.host}/ws`;
}

function el(id) {
    return document.getElementById(id);
}

function warmupBackends() {
    if (state.warmupPromise) {
        return state.warmupPromise;
    }

    state.warmupPromise = fetchWithTimeout(`${window.location.origin}/internal/warmup`, {}, 95000)
        .then(response => {
            if (!response.ok) {
                throw new Error(`Warmup failed: HTTP ${response.status}`);
            }
            return response.json().catch(() => ({}));
        })
        .catch(error => {
            console.warn('Backend warmup failed:', error);
            state.warmupPromise = null;
            throw error;
        });

    return state.warmupPromise;
}

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

function defaultParticipantName(isHost) {
    const suffix = Math.random().toString(36).slice(2, 6).toUpperCase();
    return isHost ? `Host-${suffix}` : `Guest-${suffix}`;
}

async function registerParticipant(sessionId, isHost) {
    const participantName = defaultParticipantName(isHost);
    const response = await fetchWithTimeout(`${state.sessionServiceUrl}/${encodeURIComponent(sessionId)}/join`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: participantName })
    }, 15000);

    if (!response.ok) {
        const txt = await response.text().catch(() => '');
        throw new Error(`HTTP ${response.status}${txt ? `: ${txt}` : ''}`);
    }

    return response.json();
}

function persistParticipantState() {
    if (state.participantId) {
        localStorage.setItem('participantId', state.participantId);
    }
    if (state.sessionId) {
        localStorage.setItem('sessionId', state.sessionId);
    }
    if (state.participantName) {
        localStorage.setItem('participantName', state.participantName);
    }
    localStorage.setItem('isHost', String(Boolean(state.isHost)));
}

function clearParticipantState() {
    localStorage.removeItem('participantId');
    localStorage.removeItem('sessionId');
    localStorage.removeItem('participantName');
    localStorage.removeItem('isHost');
}

function startParticipantsPolling() {
    stopParticipantsPolling();
    if (!state.sessionId) return;

    state.participantsPollId = setInterval(() => {
        if (state.sessionId) {
            loadParticipants();
        }
    }, 5000);
}

function stopParticipantsPolling() {
    if (!state.participantsPollId) return;
    clearInterval(state.participantsPollId);
    state.participantsPollId = null;
}

function fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
    return Promise.race([
        fetch(url, options),
        new Promise((_, reject) =>
            setTimeout(() => reject(new Error(`Запрос истёк (${timeoutMs}мс)`)), timeoutMs)
        )
    ]);
}

function setError(id, message) {
    const node = el(id);
    if (!node) return;
    node.textContent = message;
    node.classList.remove('hidden');
}

function clearError(id) {
    const node = el(id);
    if (!node) return;
    node.textContent = '';
    node.classList.add('hidden');
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

    ['joinError', 'createError'].forEach(clearError);
}

async function resolveSessionFromInput(rawValue) {
    const raw = rawValue.trim();
    if (!raw) {
        throw new Error('Пустая ссылка или код');
    }

    let token = raw;

    try {
        const url = new URL(raw);
        token =
            url.searchParams.get('session') ||
            url.searchParams.get('code') ||
            url.searchParams.get('id') ||
            url.pathname.split('/').filter(Boolean).pop() ||
            raw;
    } catch {
        token = raw;
    }

    const tokenEncoded = encodeURIComponent(token);

    let response = await fetchWithTimeout(`${state.sessionServiceUrl}/code?code=${tokenEncoded}`, {}, 10000);
    if (response.ok) {
        return await response.json();
    }

    response = await fetchWithTimeout(`${state.sessionServiceUrl}/${tokenEncoded}`, {}, 10000);
    if (response.ok) {
        return await response.json();
    }

    throw new Error('Сессия не найдена');
}

async function createSession() {
    if (state.isCreating) return;

    const btn = el('createBtn');
    clearError('createError');

    state.isCreating = true;
    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Создание...';
    }

    try {
        const response = await fetchWithTimeout(state.sessionServiceUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        }, 15000);

        if (!response.ok) {
            const txt = await response.text().catch(() => '');
            throw new Error(`HTTP ${response.status}${txt ? `: ${txt}` : ''}`);
        }

        const data = await response.json();
        const participant = await registerParticipant(data.sessionId, true);

        state.sessionId = data.sessionId;
        state.sessionCode = data.code;
        state.isHost = true;
        state.participantId = participant.participantId;
        state.participantName = participant.name;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {};
        state.roundResolved = false;

        persistParticipantState();

        showWaitingScreen();
        connectWebSocket();
        showToast('Сессия создана');

    } catch (error) {
        console.error('Create session error:', error);
        setError('createError', 'Не удалось создать сессию');
    } finally {
        state.isCreating = false;
        if (btn) {
            btn.disabled = false;
            btn.textContent = 'Создать сессию';
        }
    }
}

async function joinSession() {
    const input = el('sessionLink');
    clearError('joinError');

    const link = input.value.trim();
    if (!link) {
        setError('joinError', 'Вставьте ссылку сессии');
        return;
    }

    try {
        const session = await resolveSessionFromInput(link);
        const participant = await registerParticipant(session.sessionId, false);

        state.sessionId = session.sessionId;
        state.sessionCode = session.code;
        state.isHost = false;
        state.participantId = participant.participantId;
        state.participantName = participant.name;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {};
        state.roundResolved = false;

        persistParticipantState();

        showWaitingScreen();
        connectWebSocket();
        showToast('Вы подключились к сессии');
    } catch (error) {
        console.error('Join error:', error);
        setError('joinError', 'Не удалось подключиться');
    }
}

async function joinSessionDirect() {
    try {
        const token = state.sessionCode || new URLSearchParams(window.location.search).get('session');
        if (!token) return;

        const session = await resolveSessionFromInput(token);
        const existingParticipant = state.participantId && state.sessionId === session.sessionId;
        const participant = existingParticipant ? null : await registerParticipant(session.sessionId, false);

        state.sessionId = session.sessionId;
        state.sessionCode = session.code;
        state.isHost = existingParticipant ? state.isHost : false;
        state.participantId = existingParticipant ? state.participantId : participant.participantId;
        state.participantName = existingParticipant ? state.participantName : participant.name;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {};
        state.roundResolved = false;

        persistParticipantState();

        showWaitingScreen();
        connectWebSocket();
    } catch (err) {
        console.error('joinSessionDirect err', err);
        showToast('Не удалось присоединиться');
        showWelcomeScreen();
    }
}

async function loadParticipants() {
    const listEl = el('participantsList');
    if (!listEl) return;

    try {
        const response = await fetchWithTimeout(`${state.sessionServiceUrl}/${encodeURIComponent(state.sessionId)}`, {}, 10000);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const data = await response.json();
        state.participants = Array.isArray(data.participants) ? data.participants : [];
        updateParticipantsList();
    } catch (err) {
        console.warn('loadParticipants error:', err);
        state.participants = [];
        updateParticipantsList();
    }
}

function updateParticipantsList() {
    const listEl = el('participantsList');
    if (!listEl) return;

    if (!state.participants || state.participants.length === 0) {
        listEl.innerHTML = '<p style="color:#999; text-align:center; margin:0;">Участники ещё не отображаются</p>';
        return;
    }

    listEl.innerHTML = state.participants.map(p => {
        const time = p.joinedAt ? new Date(p.joinedAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '';
        const isMe = p.id === state.participantId ? ' ★' : '';
        return `
            <div style="padding: 8px 0; color: #ddd; font-size: 0.95em; border-bottom: 1px solid rgba(255,255,255,0.05);">
                ${p.name || 'Участник'}${isMe}
                ${time ? `<span style="color:#666; font-size:0.85em;">@ ${time}</span>` : ''}
            </div>
        `;
    }).join('');
}

function connectWebSocket() {
    if (!state.sessionId) {
        console.warn('connectWebSocket: no sessionId');
        return;
    }

    console.log('Connecting to WS:', state.wsUrl, 'Session:', state.sessionId);

    if (state.stompClient && state.connected) {
        try {
            state.stompClient.disconnect(() => {});
        } catch {}
        state.connected = false;
    }

    try {
        const socket = new SockJS(state.wsUrl);
        state.stompClient = Stomp.over(socket);
        state.stompClient.debug = () => {};

        state.stompClient.connect({}, (frame) => {
            console.log('WS connected:', frame);
            state.connected = true;

            loadParticipants();

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/start`, (msg) => {
                handleStartMessage(msg);
            });

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/votes`, (msg) => {
                handleVoteUpdate(msg);
            });

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/match`, (msg) => {
                handleMatchMessage(msg);
            });

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/index`, (msg) => {
                try {
                    const payload = JSON.parse(msg.body);
                    if (payload && typeof payload.movieIndex === 'number') {
                        state.currentMovieIndex = payload.movieIndex;
                        state.participantVotes = {};
                        state.voted = false;
                        state.votedDecision = null;
                        state.roundResolved = false;
                        loadCurrentMovie();
                    }
                } catch (e) {
                    console.warn('index subscription parse error', e);
                }
            });

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/participants`, () => {
                loadParticipants();
            });

        }, (err) => {
            console.error('WS connect error:', err);
            state.connected = false;
            setTimeout(() => {
                if (state.sessionId) connectWebSocket();
            }, 3000);
        });
    } catch (err) {
        console.error('connectWebSocket exception', err);
    }
}

function handleStartMessage() {
    state.votingStarted = true;
    showToast('Голосование начато!');
    initVoting().catch(err => {
        console.error('initVoting error', err);
        showToast('Ошибка запуска голосования');
    });
}

function handleVoteUpdate(message) {
    try {
        const vote = JSON.parse(message.body);
        if (!vote || !vote.participantId) return;

        state.participantVotes[vote.participantId] = vote.decision;

        if (state.roundResolved) return;

        const votes = Object.values(state.participantVotes);
        if (votes.length >= 2) {
            resolveCurrentRound();
        }
    } catch (e) {
        console.warn('handleVoteUpdate parse error', e);
    }
}

function handleMatchMessage(message) {
    try {
        const match = JSON.parse(message.body);
        state.roundResolved = true;
        showMatchScreen(match);
    } catch (e) {
        console.warn('handleMatchMessage parse error', e);
    }
}

function startVoting() {
    if (!state.isHost) {
        showToast('Только хост может начать голосование');
        return;
    }

    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/start-voting', {}, JSON.stringify({
                sessionId: state.sessionId,
                by: state.participantName || state.participantId || 'host'
            }));
        } catch (e) {
            console.warn('startVoting send failed, fallback to local', e);
            handleStartMessage();
        }
    } else {
        handleStartMessage();
    }
}

async function initVoting() {
    try {
        const resp = await fetchWithTimeout(`${state.votingServiceUrl}/movies`, {}, 15000);
        if (!resp.ok) throw new Error(`Ошибка загрузки фильмов (${resp.status})`);

        state.movies = await resp.json();

        if (!Array.isArray(state.movies) || state.movies.length === 0) {
            throw new Error('Нет доступных фильмов');
        }

        state.currentMovieIndex = 0;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {};
        state.roundResolved = false;

        loadCurrentMovie();
        showVotingScreen();

        const codeNode = el('votingSessionCode');
        if (codeNode) codeNode.textContent = state.sessionCode || '—';

        const notice = el('votingNotice');
        if (notice) notice.textContent = 'Голосование началось!';

        const yesBtn = el('yesBtn');
        const noBtn = el('noBtn');
        if (yesBtn) yesBtn.disabled = false;
        if (noBtn) noBtn.disabled = false;

    } catch (err) {
        console.error('initVoting err', err);
        showToast(err.message || 'Ошибка загрузки фильмов');
    }
}

function vote(decision) {
    if (state.voted) return;

    if (!state.votingStarted) {
        showToast('Сначала нажмите "Начать выбор"');
        return;
    }

    const movie = state.movies[state.currentMovieIndex];
    if (!movie) {
        showToast('Фильм не найден');
        return;
    }

    state.voted = true;
    state.votedDecision = decision;
    state.participantVotes[state.participantId] = decision;

    const yesBtn = el('yesBtn');
    const noBtn = el('noBtn');
    if (yesBtn) yesBtn.disabled = true;
    if (noBtn) noBtn.disabled = true;

    renderStatusRow();

    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/vote', {}, JSON.stringify({
                sessionId: state.sessionId,
                participantId: state.participantId,
                movieId: movie.id,
                decision: decision
            }));
        } catch (e) {
            console.error('Failed to send vote:', e);
        }
    } else {
        state.participantVotes[state.participantId] = decision;
        if (decision === 'LIKE') {
            state.roundResolved = true;
            setTimeout(() => {
                showMatchScreen({
                    movieTitle: movie.title,
                    posterPath: movie.posterPath
                });
            }, 300);
        } else {
            setTimeout(() => moveToNextMovie(), 300);
        }
    }
}

function resolveCurrentRound() {
    if (state.roundResolved) return;

    const votes = Object.values(state.participantVotes);
    if (votes.length < 2) return;

    state.roundResolved = true;

    if (votes.every(v => v === 'LIKE')) {
        const movie = state.movies[state.currentMovieIndex];
        setTimeout(() => {
            showMatchScreen({
                movieTitle: movie?.title || 'Фильм',
                posterPath: movie?.posterPath
            });
        }, 400);
    } else {
        setTimeout(() => moveToNextMovie(), 400);
    }
}

function moveToNextMovie() {
    if (!state.movies.length) return;

    state.participantVotes = {};
    state.voted = false;
    state.votedDecision = null;
    state.roundResolved = false;
    state.currentMovieIndex = (state.currentMovieIndex + 1) % state.movies.length;

    loadCurrentMovie();

    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/update-movie-index', {}, JSON.stringify({
                sessionId: state.sessionId,
                movieIndex: state.currentMovieIndex
            }));
        } catch (e) {
            console.warn('Failed to send update-movie-index', e);
        }
    }
}

function loadCurrentMovie() {
    const movie = state.movies[state.currentMovieIndex];
    if (!movie) {
        const movieTitle = el('movieTitle');
        const moviePoster = el('moviePoster');
        if (movieTitle) movieTitle.textContent = 'Фильм не найден';
        if (moviePoster) moviePoster.src = '';
        return;
    }

    const movieTitle = el('movieTitle');
    const movieMeta = el('movieMeta');
    const movieCounter = el('movieCounter');
    const moviePoster = el('moviePoster');
    const yesBtn = el('yesBtn');
    const noBtn = el('noBtn');

    if (movieTitle) movieTitle.textContent = movie.title || 'Без названия';
    if (movieMeta) movieMeta.textContent = movie.overview ? movie.overview.substring(0, 100) + '...' : '';
    if (movieCounter) movieCounter.textContent = `Фильм ${state.currentMovieIndex + 1} из ${state.movies.length}`;
    if (moviePoster) {
        moviePoster.src = movie.posterPath
            ? `https://image.tmdb.org/t/p/w500${movie.posterPath}`
            : 'https://via.placeholder.com/340x510?text=No+Image';
        moviePoster.alt = movie.title || 'Poster';
    }

    if (yesBtn) yesBtn.disabled = false;
    if (noBtn) noBtn.disabled = false;
}

function showMatchScreen(match) {
    hideAllScreens();
    const matchTitle = el('matchTitle');
    const matchMovieTitle = el('matchMovieTitle');
    const matchPoster = el('matchPoster');
    const matchScreen = el('matchScreen');

    if (matchTitle) matchTitle.textContent = 'Совпадение';
    if (matchMovieTitle) matchMovieTitle.textContent = match.movieTitle || 'Фильм';

    const url = match.posterPath
        ? `https://image.tmdb.org/t/p/w500${match.posterPath}`
        : 'https://via.placeholder.com/260x390?text=No+Image';

    if (matchPoster) matchPoster.src = url;
    if (matchScreen) matchScreen.classList.add('active');

    playConfetti();
}

function nextMatch() {
    state.roundResolved = false;
    hideAllScreens();
    moveToNextMovie();
    showVotingScreen();
}

function hideAllScreens() {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
}

function showWelcomeScreen() {
    hideAllScreens();
    const welcome = el('welcomeScreen');
    if (welcome) welcome.classList.add('active');
}

function showWaitingScreen() {
    hideAllScreens();
    startParticipantsPolling();

    const token = state.sessionCode || state.sessionId;
    const inviteLink = el('inviteLink');
    if (inviteLink) inviteLink.value = `${window.location.origin}/?session=${encodeURIComponent(token)}`;

    const waitingScreen = el('waitingScreen');
    if (waitingScreen) waitingScreen.classList.add('active');

    const codeText = el('waitingCodeText');
    if (codeText) codeText.textContent = state.sessionCode || '—';

    if (token) {
        try {
            window.history.replaceState({}, '', `?session=${encodeURIComponent(token)}`);
        } catch {}
    }

    loadParticipants();
}

function showVotingScreen() {
    hideAllScreens();
    state.votingStarted = true;
    const votingScreen = el('votingScreen');
    if (votingScreen) votingScreen.classList.add('active');
}

function copyToClipboard(btn) {
    const link = el('inviteLink').value;
    const button = btn || document.getElementById('copyInviteBtn') || document.activeElement;

    if (!navigator.clipboard) {
        const tmp = document.createElement('textarea');
        tmp.value = link;
        document.body.appendChild(tmp);
        tmp.select();
        try {
            document.execCommand('copy');
            showToast('Скопировано!');
        } catch {}
        tmp.remove();
        return;
    }

    navigator.clipboard.writeText(link).then(() => {
        if (button) {
            const original = button.textContent;
            button.textContent = 'Скопировано!';
            setTimeout(() => button.textContent = original, 1500);
        } else {
            showToast('Скопировано!');
        }
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

function exitSession() {
    stopParticipantsPolling();

    if (state.stompClient && state.connected) {
        try {
            state.stompClient.disconnect();
        } catch {}
        state.connected = false;
    }

    state.sessionId = null;
    state.sessionCode = null;
    state.participantId = null;
    state.participantName = null;
    state.votingStarted = false;
    state.voted = false;
    state.votedDecision = null;
    state.participantVotes = {};
    state.roundResolved = false;

    clearParticipantState();

    showWelcomeScreen();
}

function goBackToWelcome() {
    exitSession();
}

function playConfetti() {
    const count = 30;
    for (let i = 0; i < count; i++) {
        const c = document.createElement('div');
        c.style.position = 'fixed';
        c.style.top = '-10px';
        c.style.left = Math.random() * 100 + '%';
        c.style.width = (6 + Math.random() * 10) + 'px';
        c.style.height = (10 + Math.random() * 16) + 'px';
        c.style.background = ['#6366f1', '#4f46e5', '#10b981', '#06b6d4', '#f97316'][Math.floor(Math.random() * 5)];
        c.style.opacity = 0.95;
        c.style.borderRadius = '2px';
        c.style.zIndex = 9999;
        c.style.pointerEvents = 'none';
        c.style.transition = 'transform 2.4s ease, top 2.4s ease, opacity 2.4s ease';
        document.body.appendChild(c);

        setTimeout(() => {
            c.style.top = (100 + Math.random() * 40) + 'vh';
            c.style.transform = `translateX(${Math.random() * 60 - 30}vw) rotate(${Math.random() * 720}deg)`;
            c.style.opacity = 0;
        }, 50);

        setTimeout(() => c.remove(), 2600);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const savedPid = localStorage.getItem('participantId');
    const savedSid = localStorage.getItem('sessionId');
    const savedParticipantName = localStorage.getItem('participantName');
    const savedIsHost = localStorage.getItem('isHost') === 'true';

    if (savedPid && savedSid) {
        state.participantId = savedPid;
        state.sessionId = savedSid;
        state.participantName = savedParticipantName;
        state.isHost = savedIsHost;
    }

    checkUrlParams();
    switchTab('join');
    warmupBackends().catch(() => {});
});

function checkUrlParams() {
    const params = new URLSearchParams(window.location.search);
    const session = params.get('session');
    if (session) {
        state.sessionCode = session;
        joinSessionDirect();
    }
}

function renderStatusRow() {
    const row = el('statusRow');
    if (!row) return;

    row.innerHTML = '';

    if (!state.participants || state.participants.length === 0) {
        row.textContent = 'Статус голосов обновляется...';
        return;
    }

    state.participants.forEach(p => {
        const div = document.createElement('div');
        div.className = 'status-item';
        const voted = state.participantVotes[p.id];
        div.textContent = (p.id === state.participantId)
            ? 'Вы: ' + (voted ? 'Проголосовал' : 'Ожидает')
            : (voted ? 'Проголосовал' : 'Ожидает');
        row.appendChild(div);
    });
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
