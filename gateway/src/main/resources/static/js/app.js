// ====== STATE ======
const state = {
    sessionId: null,
    participantId: null,
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
    sessionServiceUrl: window.BACKEND_SESSION_URL || `${window.location.origin}/api/sessions`,
    votingServiceUrl: window.BACKEND_VOTING_URL || `${window.location.origin}/api/voting`,
    wsUrl: window.BACKEND_WS_URL || getDefaultWsUrl(),
};

// ====== HELPERS ======
function getDefaultWsUrl() {
    const proto = window.location.protocol;
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

function fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
    return Promise.race([
        fetch(url, options),
        new Promise((_, reject) =>
            setTimeout(() => reject(new Error(`Запрос истёк (${timeoutMs}мс)`)), timeoutMs)
        )
    ]);
}

// ====== TABS ======
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

// ====== CREATE / JOIN ======
async function createSession() {
    try {
        const btn = el('createBtn');
        if (btn) { btn.disabled = true; btn.textContent = 'Создание...'; }

        const response = await fetchWithTimeout(state.sessionServiceUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
        }, 15000);

        if (!response.ok) throw new Error(`Ошибка создания сессии (${response.status})`);

        const data = await response.json();
        state.sessionId = data.sessionId || data.id || null;
        state.sessionCode = data.code || (state.sessionId ? state.sessionId.substring(0,6).toUpperCase() : '—');
        state.isHost = true;
        state.participantId = generateParticipantId();

        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);

        showWaitingScreen();
        connectWebSocket();

        if (btn) { btn.textContent = 'Создать сессию'; btn.disabled = false; }
    } catch (err) {
        console.error('createSession err', err);
        const node = el('createError');
        if (node) { node.textContent = err.message || 'Не удалось создать сессию'; node.classList.remove('hidden'); }
        const btn = el('createBtn');
        if (btn) { btn.disabled = false; btn.textContent = 'Создать сессию'; }
    }
}

async function joinSession() {
    try {
        const link = el('sessionLink').value.trim();
        if (!link) throw new Error('Введите ссылку сессии');

        let sessionId;
        try {
            const u = new URL(link);
            sessionId = u.searchParams.get('session') || u.searchParams.get('id');
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
        state.isHost = false;
        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);
        showWaitingScreen();
        connectWebSocket();
    } catch (err) {
        console.error('joinSessionDirect err', err);
        showToast('Не удалось присоединиться');
        showWelcomeScreen();
    }
}

// ====== WEBSOCKET ======
function connectWebSocket() {
    if (!state.sessionId) {
        console.warn('connectWebSocket: no sessionId');
        return;
    }

    console.log('🔌 Connecting to WS:', state.wsUrl, 'Session:', state.sessionId);

    try {
        const socket = new SockJS(state.wsUrl);
        state.stompClient = Stomp.over(socket);
        state.stompClient.debug = () => {};

        state.stompClient.connect({}, (frame) => {
            console.log('✅ WS connected:', frame);
            state.connected = true;

            // Подписка на старт голосования
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/start`, (msg) => {
                console.log('🎬 Start message received');
                handleStartMessage(msg);
            });

            // Подписка на голоса
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/votes`, (msg) => {
                console.log('🗳️ Vote received');
                handleVoteUpdate(msg);
            });

            // Подписка на совпадения
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/match`, (msg) => {
                console.log('🎉 Match received');
                handleMatchMessage(msg);
            });

            // Подписка на обновление индекса текущего фильма (синхронизация между участниками)
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/index`, (msg) => {
                try {
                    const payload = JSON.parse(msg.body);
                    console.log('🔁 Received index update', payload);
                    if (payload && typeof payload.movieIndex === 'number') {
                        state.currentMovieIndex = payload.movieIndex;
                        loadCurrentMovie();
                    }
                } catch (e) {
                    console.warn('index subscription parse error', e);
                }
            });

        }, (err) => {
            console.error('❌ WS connect error:', err);
            state.connected = false;
            setTimeout(() => {
                if (state.sessionId) connectWebSocket();
            }, 3000);
        });
    } catch (err) {
        console.error('connectWebSocket exception', err);
    }
}

// ====== HANDLERS ======
function handleStartMessage(payload) {
    console.log('🎬 handleStartMessage called');
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
        console.log('🗳️ Vote:', vote);

        if (!vote || !vote.participantId) return;

        // Простая логика: если оба проголосовали LIKE - показываем совпадение
        state.participantVotes = state.participantVotes || {};
        state.participantVotes[vote.participantId] = vote.decision;

        const votes = Object.values(state.participantVotes);
        if (votes.length >= 2 && votes.every(v => v === 'LIKE')) {
            const movie = state.movies[state.currentMovieIndex];
            setTimeout(() => {
                showMatchScreen({ movieTitle: movie?.title || 'Фильм', posterPath: movie?.posterPath });
            }, 800);
        } else if (votes.length >= 2) {
            setTimeout(() => moveToNextMovie(), 800);
        }
    } catch (e) {
        console.warn('handleVoteUpdate parse error', e);
    }
}

function handleMatchMessage(message) {
    try {
        const match = JSON.parse(message.body);
        showMatchScreen(match);
    } catch (e) {
        console.warn('handleMatchMessage parse error', e);
    }
}

// ====== VOTING ======
function startVoting() {
    console.log('🎬 startVoting called, isHost:', state.isHost, 'connected:', state.connected);

    if (!state.isHost) {
        showToast('Только хост может начать голосование');
        return;
    }

    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/start-voting', {}, JSON.stringify({
                sessionId: state.sessionId,
                by: state.participantId
            }));
            console.log('📤 Sent start-voting message');
        } catch (e) {
            console.warn('startVoting send failed, fallback to local', e);
            handleStartMessage({});
        }
    } else {
        console.warn('WS not connected, starting locally');
        handleStartMessage({});
    }
}

async function initVoting() {
    try {
        console.log('🍿 initVoting: loading movies...');

        const resp = await fetch(`${state.votingServiceUrl}/movies`);
        if (!resp.ok) throw new Error(`Ошибка загрузки фильмов (${resp.status})`);

        state.movies = await resp.json();
        console.log('✅ Movies loaded:', state.movies.length);

        if (!Array.isArray(state.movies) || state.movies.length === 0) {
            throw new Error('Нет доступных фильмов');
        }

        state.currentMovieIndex = 0;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {};

        loadCurrentMovie();
        showVotingScreen();

        const codeNode = el('votingSessionCode');
        if (codeNode) codeNode.textContent = state.sessionCode || '—';

        el('votingNotice').textContent = 'Голосование началось!';
        el('yesBtn').disabled = false;
        el('noBtn').disabled = false;

    } catch (err) {
        console.error('initVoting err', err);
        showToast(err.message || 'Ошибка загрузки фильмов');
    }
}

function vote(decision) {
    // 🔧 DEBUG: Логируем каждый шаг
    console.log('🗳️ vote() called:', { decision, voted: state.voted, votingStarted: state.votingStarted });

    if (state.voted) {
        console.warn('⚠️ Already voted for this movie');
        return;
    }
    if (!state.votingStarted) {
        console.error('❌ votingStarted is false — did you click "Начать выбор"?');
        showToast('Сначала нажмите "Начать выбор"');
        return;
    }

    const movie = state.movies[state.currentMovieIndex];
    if (!movie) {
        console.error('❌ No movie at index', state.currentMovieIndex);
        return;
    }

    console.log('✅ Voting for movie:', movie.title);

    // Обновляем состояние
    state.voted = true;
    state.votedDecision = decision;
    state.participantVotes[state.participantId] = decision;

    // Обновляем UI
    el('yesBtn').disabled = true;
    el('noBtn').disabled = true;
    updateParticipantStatusById(state.participantId, 'voted');
    renderStatusRow();

    // 🔧 DEBUG: Проверка логики "все проголосовали"
    const votesCount = Object.keys(state.participantVotes).length;
    const participantsCount = state.participants?.length || 1;
    console.log('📊 Votes check:', { votesCount, participantsCount, allVotes: state.participantVotes });

    // Локальная проверка (для одного игрока)
    if (votesCount >= Math.max(1, participantsCount)) {
        const allLike = Object.values(state.participantVotes).every(v => v === 'LIKE');
        console.log('🎯 All voted, allLike:', allLike);

        if (allLike) {
            console.log('🎉 Showing match screen');
            setTimeout(() => showMatchScreen({
                movieTitle: movie.title,
                posterPath: movie.posterPath
            }), 400);
        } else {
            console.log('➡️ Moving to next movie');
            setTimeout(() => moveToNextMovie(), 400);
        }
    }

    // Отправка на сервер
    if (state.connected && state.stompClient) {
        console.log('📤 Sending vote to server');
        try {
            state.stompClient.send('/app/vote', {}, JSON.stringify({
                sessionId: state.sessionId,
                participantId: state.participantId,
                movieId: movie.id,
                decision: decision
            }));
        } catch (e) {
            console.error('❌ Failed to send vote:', e);
        }
    } else {
        console.warn('⚠️ WebSocket not connected, vote not sent to server');
    }
}

function checkAllVotedAndProceed(movie) {
    const required = state.participants.length || 2; // минимум 2, но если один — то 1
    const votedCount = Object.keys(state.participantVotes).length;

    if (votedCount >= required) {
        const votes = Object.values(state.participantVotes);

        // Если все проголосовали LIKE — показываем совпадение
        if (votes.length > 0 && votes.every(v => v === 'LIKE')) {
            setTimeout(() => {
                showMatchScreen({
                    movieTitle: movie.title,
                    posterPath: movie.posterPath
                });
            }, 400);
        }

        else if (votedCount >= required) {
            setTimeout(() => moveToNextMovie(), 400);
        }
    }
}

function moveToNextMovie() {
    state.participantVotes = {};
    state.voted = false;
    state.votedDecision = null;
    state.currentMovieIndex = (state.currentMovieIndex + 1) % state.movies.length;
    loadCurrentMovie();
    // отправляем на сервер обновлённый индекс, чтобы все клиенты синхронизировались
    if (state.connected && state.stompClient) {
        try {
            state.stompClient.send('/app/update-movie-index', {}, JSON.stringify({
                sessionId: state.sessionId,
                movieIndex: state.currentMovieIndex
            }));
            console.log('📤 Sent update-movie-index', state.currentMovieIndex);
        } catch (e) {
            console.warn('Failed to send update-movie-index', e);
        }
    }
}

function loadCurrentMovie() {
    const movie = state.movies[state.currentMovieIndex];
    if (!movie) {
        el('movieTitle').textContent = 'Фильм не найден';
        el('moviePoster').src = '';
        return;
    }

    el('movieTitle').textContent = movie.title || 'Без названия';
    el('movieMeta').textContent = movie.overview ? movie.overview.substring(0, 100) + '...' : '';
    el('movieCounter').textContent = `Фильм ${state.currentMovieIndex+1} из ${state.movies.length}`;

    const imageUrl = movie.posterPath
        ? `https://image.tmdb.org/t/p/w500${movie.posterPath}`
        : 'https://via.placeholder.com/340x510?text=No+Image';

    el('moviePoster').src = imageUrl;
    el('moviePoster').alt = movie.title || 'Poster';

    el('yesBtn').disabled = false;
    el('noBtn').disabled = false;
}

// ====== MATCH ======
function showMatchScreen(match) {
    hideAllScreens();
    el('matchTitle').textContent = 'Совпадение 🎉';
    el('matchMovieTitle').textContent = match.movieTitle || 'Фильм';

    const url = match.posterPath
        ? `https://image.tmdb.org/t/p/w500${match.posterPath}`
        : 'https://via.placeholder.com/260x390?text=No+Image';

    el('matchPoster').src = url;
    el('matchScreen').classList.add('active');
    playConfetti();
}

function nextMatch() {
    state.voted = false;
    state.votedDecision = null;
    state.participantVotes = {};
    el('yesBtn').disabled = false;
    el('noBtn').disabled = false;
    hideAllScreens();
    el('votingScreen').classList.add('active');
    loadCurrentMovie();
}

// ====== NAVIGATION ======
function hideAllScreens() {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
}

function showWelcomeScreen() {
    hideAllScreens();
    el('welcomeScreen').classList.add('active');
}

function showWaitingScreen() {
    hideAllScreens();
    el('inviteLink').value = `${window.location.origin}/?session=${state.sessionId}`;
    el('waitingCodeText').textContent = state.sessionCode || '—';
    el('waitingScreen').classList.add('active');
}

function showVotingScreen() {
    hideAllScreens();
    state.votingStarted = true;
    el('votingScreen').classList.add('active');
}

// ====== UTILS ======
function copyToClipboard(btn) {
    const link = el('inviteLink').value;
    const button = btn || document.getElementById('copyInviteBtn') || document.activeElement;

    if (!navigator.clipboard) {
        const tmp = document.createElement('textarea');
        tmp.value = link;
        document.body.appendChild(tmp);
        tmp.select();
        try { document.execCommand('copy'); showToast('Скопировано!'); } catch (e) {}
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
    if (!navigator.clipboard) { showToast('Clipboard API недоступен'); return; }
    navigator.clipboard.readText().then(t => { el('sessionLink').value = t; });
}

function exitSession() {
    if (state.stompClient && state.connected) {
        try { state.stompClient.disconnect(); } catch (e) {}
        state.connected = false;
    }
    state.sessionId = null;
    state.participantId = null;
    state.votingStarted = false;
    localStorage.removeItem('participantId');
    localStorage.removeItem('sessionId');
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
        c.style.background = ['#6366f1','#4f46e5','#10b981','#06b6d4','#f97316'][Math.floor(Math.random()*5)];
        c.style.opacity = 0.95;
        c.style.borderRadius = '2px';
        c.style.zIndex = 9999;
        c.style.pointerEvents = 'none';
        c.style.transition = 'transform 2.4s ease, top 2.4s ease, opacity 2.4s ease';
        document.body.appendChild(c);

        setTimeout(() => {
            c.style.top = (100 + Math.random()*40) + 'vh';
            c.style.transform = `translateX(${Math.random()*60 - 30}vw) rotate(${Math.random()*720}deg)`;
            c.style.opacity = 0;
        }, 50);
        setTimeout(() => c.remove(), 2600);
    }
}

// ====== INIT ======
document.addEventListener('DOMContentLoaded', () => {
    const savedPid = localStorage.getItem('participantId');
    const savedSid = localStorage.getItem('sessionId');

    if (savedPid && savedSid) {
        state.participantId = savedPid;
        state.sessionId = savedSid;
    }

    checkUrlParams();
    switchTab('join');
});

function checkUrlParams() {
    const params = new URLSearchParams(window.location.search);
    const sessionId = params.get('session');
    if (sessionId) {
        state.sessionId = sessionId;
        joinSessionDirect();
    }
}
function renderStatusRow() {
    const row = el('statusRow');
    if (!row) return;
    row.innerHTML = '';
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

// ====== EXPORTS ======
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