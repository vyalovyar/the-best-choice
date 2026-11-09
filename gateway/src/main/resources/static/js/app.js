const state = {
    sessionId: null,
    participantId: null,
    participantName: null,
    sessionCode: null,
    isHost: false,
    participantToken: null,
    hostToken: null,
    heartbeatId: null,
    kickedOut: false,
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
    votingWarmupPromise: null,
    votingInitPromise: null,
    votingInitAttempts: 0,
    startRequestSent: false,
    movieFetchIdx: null,
    leaveSent: false,
    participantsSig: null,
    participantsPrevIds: [],
};

function getDefaultWsUrl() {
    return `${window.location.origin}/ws`;
}

function el(id) {
    return document.getElementById(id);
}

/* ═══════════════════════════════════════════════
   НИКНЕЙМ: модалка перед входом в комнату
   ═══════════════════════════════════════════════ */
const NICK_KEY = 'tbcNickname';
let nickContext = null;   // { sessionId, isHost, session }
let nickTimer = null;
let nickTaken = false;

function getSavedNickname() {
    try { return localStorage.getItem(NICK_KEY) || ''; } catch (e) { return ''; }
}

function openNicknameModal(ctx) {
    const overlay = el('nickOverlay');
    const input = el('nickInput');
    if (!overlay || !input) return;

    nickContext = ctx;
    nickTaken = false;
    input.value = getSavedNickname();
    setNickError('');
    setNickStatus('');
    updateNickButton();
    overlay.classList.remove('hidden');
    scheduleNickCheck();
    setTimeout(() => { try { input.focus(); } catch (e) {} }, 60);
}

function closeNicknameModal() {
    const overlay = el('nickOverlay');
    if (overlay) overlay.classList.add('hidden');
    if (nickTimer) { clearTimeout(nickTimer); nickTimer = null; }
    nickContext = null;
    nickTaken = false;
}

function cancelNickname() {
    closeNicknameModal();
}

function setNickError(msg) {
    const node = el('nickError');
    if (!node) return;
    node.textContent = msg || '';
    node.classList.toggle('hidden', !msg);
}

function setNickStatus(msg) {
    const node = el('nickStatus');
    if (!node) return;
    node.textContent = msg || '';
    node.classList.toggle('hidden', !msg);
}

function updateNickButton() {
    const btn = el('nickConfirmBtn');
    const input = el('nickInput');
    if (!btn || !input) return;
    const name = input.value.trim();
    btn.disabled = nickTaken || name.length < 2;
}

function onNickInput() {
    setNickError('');
    setNickStatus('');
    nickTaken = false;
    updateNickButton();
    scheduleNickCheck();
}

function scheduleNickCheck() {
    if (nickTimer) { clearTimeout(nickTimer); nickTimer = null; }
    const input = el('nickInput');
    if (!input || !nickContext) return;
    const name = input.value.trim();
    if (name.length < 2) return;
    nickTimer = setTimeout(() => {
        nickTimer = null;
        checkNameAvailable(nickContext.sessionId, name);
    }, 400);
}

/* Проверка занятости ника (debounce 400 мс).
   Fail-open: если эндпоинт недоступен — не блокируем вход,
   страховкой служит 409/NAME_TAKEN на join. */
async function fetchNameAvailable(sessionId, name) {
    try {
        const resp = await fetchWithTimeout(
            `${state.sessionServiceUrl}/${encodeURIComponent(sessionId)}/name-available?name=${encodeURIComponent(name)}`,
            {}, 6000
        );
        const data = await resp.json().catch(() => ({}));
        return {
            reachable: true,
            available: data.available === true,
            code: typeof data.code === 'string' ? data.code : '',
            error: typeof data.error === 'string' ? data.error : ''
        };
    } catch (e) {
        return { reachable: false, available: false, error: '' };
    }
}

async function checkNameAvailable(sessionId, name) {
    const input = el('nickInput');
    if (!input || input.value.trim() !== name || !nickContext) return;

    const result = await fetchNameAvailable(sessionId, name);
    if (!result.reachable) return;

    if (!nickContext || !input || input.value.trim() !== name) return;

    if (result.code === 'VOTING_STARTED') {
        nickTaken = true;
        setNickStatus('');
        setNickError(result.error || 'Голосование уже началось — войти нельзя');
        return;
    }

    if (result.available) {
        nickTaken = false;
        setNickError('');
        setNickStatus('Ник свободен');
    } else {
        nickTaken = true;
        setNickStatus('');
        setNickError(result.error || 'Этот ник уже занят в зале — выберите другой');
    }
    updateNickButton();
}

async function confirmNickname() {
    const input = el('nickInput');
    const btn = el('nickConfirmBtn');
    if (!nickContext || !input || !btn) return;

    const name = (input.value || '').trim();

    if (nickTaken) {
        setNickError('Этот ник уже занят — выберите другой');
        return;
    }
    if (name.length < 2) {
        setNickError('Ник должен быть не короче 2 символов');
        return;
    }

    btn.disabled = true;
    const originalLabel = btn.textContent;
    btn.textContent = 'Входим…';

    /* Финальная проверка ника перед входом: пока пользователь думал,
       имя мог занять кто-то другой. */
    const ctxId = nickContext.sessionId;
    const finalCheck = await fetchNameAvailable(ctxId, name);
    if (finalCheck.reachable && !finalCheck.available) {
        nickTaken = true;
        setNickStatus('');
        setNickError(finalCheck.error || 'Этот ник уже занят в зале — выберите другой');
        btn.textContent = originalLabel;
        updateNickButton();
        return;
    }

    try {
        const participant = await registerParticipant(nickContext.sessionId, nickContext.isHost, name);
        try { localStorage.setItem(NICK_KEY, name); } catch (e) {}
        const ctx = nickContext;
        closeNicknameModal();
        enterRoom(
            ctx.session || { sessionId: ctx.sessionId },
            participant,
            ctx.isHost,
            ctx.isHost ? 'Сессия создана' : 'Вы подключились к сессии'
        );
    } catch (err) {
        console.error('Nickname join error:', err);
        if (err && err.nameTaken) {
            nickTaken = true;
            setNickError(err.message);
        } else if (err && err.votingLocked) {
            /* Вход в идущее голосование закрыт: закрываем модалку и
               возвращаем на главный экран, объяснив причину. */
            closeNicknameModal();
            showWelcomeScreen();
            showToast('Голосование уже началось — войти в эту комнату нельзя');
            return;
        } else {
            setNickError(err.message || 'Не удалось войти. Попробуйте ещё раз.');
        }
        btn.textContent = originalLabel;
        updateNickButton();
    }
}

function initNicknameModal() {
    const input = el('nickInput');
    const confirmBtn = el('nickConfirmBtn');
    const cancelBtn = el('nickCancelBtn');
    if (!input || !confirmBtn || !cancelBtn) return;

    input.addEventListener('input', onNickInput);
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') confirmNickname();
    });
    confirmBtn.addEventListener('click', confirmNickname);
    cancelBtn.addEventListener('click', cancelNickname);
    document.addEventListener('keydown', (e) => {
        const overlay = el('nickOverlay');
        if (overlay && !overlay.classList.contains('hidden') && e.key === 'Escape') cancelNickname();
    });
}

/* ═══════════════════════════════════════════════ */

function warmupBackends() {
    if (state.warmupPromise) {
        return state.warmupPromise;
    }

    state.warmupPromise = fetchWithTimeout(`${window.location.origin}/internal/warmup`, {}, 190000)
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

function defaultParticipantName(isHost) {
    const suffix = Math.random().toString(36).slice(2, 6).toUpperCase();
    return isHost ? `Host-${suffix}` : `Guest-${suffix}`;
}

async function registerParticipant(sessionId, isHost, name, token) {
    const participantName = (name || '').trim() || DEFAULT_NAME;
    const body = { name: participantName };
    if (token) body.token = token;
    /* Право хоста подтверждает только серверный токен сессии. */
    if (isHost === true && state.hostToken) body.hostToken = state.hostToken;

    const response = await fetchWithTimeout(`${state.sessionServiceUrl}/${encodeURIComponent(sessionId)}/join`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    }, 15000);

    if (response.ok) {
        return response.json();
    }

    /* Разбираем тело ошибки: 409 / NAME_TAKEN и любые тексты */
    let errBody = {};
    try { errBody = await response.json(); } catch (e) { /* тело не JSON */ }

    const code = String(errBody.error || errBody.code || '').toUpperCase();
    if (response.status === 409 || code.indexOf('NAME_TAKEN') !== -1) {
        const err = new Error('Этот ник уже занят в зале — выберите другой');
        err.nameTaken = true;
        throw err;
    }
    if (response.status === 403 || code.indexOf('VOTING_STARTED') !== -1) {
        const err = new Error('Голосование уже началось — войти нельзя');
        err.votingLocked = true;
        throw err;
    }

    const msg = (typeof errBody.message === 'string' && errBody.message) ||
        (typeof errBody.error === 'string' && errBody.error) ||
        `HTTP ${response.status}`;
    throw new Error(msg);
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
    if (state.participantToken) {
        localStorage.setItem('participantToken', state.participantToken);
    }
    localStorage.setItem('isHost', String(Boolean(state.isHost)));
    if (state.hostToken) localStorage.setItem('hostToken', state.hostToken);
}

function getSavedToken() {
    try { return localStorage.getItem('participantToken') || ''; } catch (e) { return ''; }
}

/* Сбрасываем только личность в комнате, сохраняя ник для формы. */
function clearParticipantSession() {
    stopHeartbeat();
    try { sessionStorage.removeItem('yakTabSession'); } catch (e) {}
    localStorage.removeItem('participantId');
    localStorage.removeItem('sessionId');
    localStorage.removeItem('participantToken');
    localStorage.removeItem('isHost');
    localStorage.removeItem('hostToken');
    state.hostToken = null;
    state.participantId = null;
    state.sessionId = null;
    state.participantToken = null;
    state.isHost = false;
    state.leaveSent = false;
}

function upsertCurrentParticipant(joinedAt = null) {
    if (!state.participantId) {
        return;
    }

    const currentParticipant = {
        id: state.participantId,
        name: state.participantName || 'Участник',
        joinedAt
    };

    const others = (state.participants || []).filter(p => p.id !== state.participantId);
    state.participants = [currentParticipant, ...others];
    updateParticipantsList();
}

function clearParticipantState() {
    stopHeartbeat();
    try {
        sessionStorage.removeItem('yakTabSession');
        sessionStorage.removeItem('yakUnloading');
    } catch (e) {}
    localStorage.removeItem('participantId');
    localStorage.removeItem('sessionId');
    localStorage.removeItem('participantName');
    localStorage.removeItem('participantToken');
    localStorage.removeItem('isHost');
    state.participantToken = null;
}

function startParticipantsPolling() {
    stopParticipantsPolling();
    if (!state.sessionId) return;

    state.participantsPollId = setInterval(() => {
        if (state.sessionId) {
            loadParticipants();
        }
    }, 2000);
}

function stopParticipantsPolling() {
    if (!state.participantsPollId) return;
    clearInterval(state.participantsPollId);
    state.participantsPollId = null;
}

function setStartButtonLoading(isLoading) {
    const startBtn = el('startBtn');
    if (!startBtn) return;
    startBtn.disabled = isLoading;
    startBtn.textContent = isLoading ? 'Запускаем...' : 'Начать выбор';
}

function formatJoinedAt(joinedAt) {
    if (!joinedAt) {
        return '';
    }

    const parsed = new Date(joinedAt);
    if (Number.isNaN(parsed.getTime())) {
        return '';
    }

    return parsed.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

async function markSessionVotingStarted() {
    const query = `requesterId=${encodeURIComponent(state.participantId || '')}`
        + (state.hostToken ? `&hostToken=${encodeURIComponent(state.hostToken)}` : '');
    const response = await fetchWithTimeout(`${state.sessionServiceUrl}/${encodeURIComponent(state.sessionId)}/start?${query}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        cache: 'no-store'
    }, 15000);

    if (!response.ok) {
        const txt = await response.text().catch(() => '');
        throw new Error(`HTTP ${response.status}${txt ? `: ${txt}` : ''}`);
    }

    return response.json();
}

function fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
    const requestOptions = { cache: 'no-store', ...options };
    return Promise.race([
        fetch(url, requestOptions),
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

/* ── Общий вход в комнату после успешной регистрации ── */
function enterRoom(session, participant, isHost, toastText) {
    state.sessionId = session.sessionId;
    state.sessionCode = session.code || state.sessionCode;
    state.isHost = isHost === true;
    state.participantId = participant.participantId;
    state.participantName = participant.name;
    state.participantToken = participant.token || state.participantToken;
    state.currentMovieIndex = Number.isInteger(session.currentMovieIndex) ? session.currentMovieIndex : 0;
    state.voted = false;
    state.votedDecision = null;
    state.participantVotes = {};
    state.roundResolved = false;
    state.leaveSent = false;
    state.kickedOut = false;
    state.participantsSig = null;
    state.participantsPrevIds = [];
    state.movieFetchIdx = null;
    try { sessionStorage.setItem('yakTabSession', session.sessionId); } catch (e) {}

    persistParticipantState();
    upsertCurrentParticipant(participant.joinedAt || null);

    showWaitingScreen();
    connectWebSocket();
    startHeartbeat();

    if (toastText) showToast(toastText);
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
        state.hostToken = data.hostToken || null;
        /* Сессия создана — теперь спрашиваем ник и входим */
        openNicknameModal({
            sessionId: data.sessionId,
            isHost: true,
            session: data
        });
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
        if (!ensureJoinable(session)) return;
        /* Сессия найдена — спрашиваем ник (с проверкой занятости) и входим */
        openNicknameModal({
            sessionId: session.sessionId,
            isHost: false,
            session: session
        });
    } catch (error) {
        console.error('Join error:', error);
        setError('joinError', 'Не удалось подключиться');
    }
}

/* В идущее голосование вход закрыт: не открываем модалку зря. */
function ensureJoinable(session) {
    if (session && session.votingStarted === true) {
        showToast('Голосование уже началось — войти в эту комнату нельзя');
        showWelcomeScreen();
        return false;
    }
    return true;
}

async function joinSessionDirect() {
    try {
        const token = state.sessionCode || new URLSearchParams(window.location.search).get('session');
        if (!token) return;

        const session = await resolveSessionFromInput(token);
        if (!ensureJoinable(session)) return;

        /* Ник спрашиваем у всех, кто открыл ссылку в этой вкладке.
           Сохранённый ник лишь подставляется в поле. */
        openNicknameModal({
            sessionId: session.sessionId,
            isHost: false,
            session: session
        });
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
        const query = state.participantId
            ? `?participantId=${encodeURIComponent(state.participantId)}`
            : '';
        const response = await fetchWithTimeout(
            `${state.sessionServiceUrl}/${encodeURIComponent(state.sessionId)}/participants${query}`,
            {}, 10000
        );

        /* 404 — нас уже нет в комнате: кикнули, истёк таймаут или комната закрыта. */
        if (response.status === 404 && state.participantId) {
            handleRemovedFromRoom();
            return;
        }
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const data = await response.json();
        state.participants = Array.isArray(data.participants) ? data.participants : [];

        /* Сервер удалил нашу запись — не изображаем присутствие дальше. */
        if (state.participantId && !state.participants.some(p => p.id === state.participantId)) {
            handleRemovedFromRoom();
            return;
        }

        updateParticipantsList();

        if (data.votingStarted === true
            && (!state.votingStarted || state.voted)
            && Number.isInteger(data.currentMovieIndex)
            && data.currentMovieIndex !== state.currentMovieIndex) {
            applyMovieIndexUpdate(data.currentMovieIndex);
        }

        if (data.votingStarted === true && !state.votingStarted) {
            beginVotingStart();
        }
    } catch (err) {
        console.warn('loadParticipants error:', err);
        if (!state.participants || state.participants.length === 0) {
            updateParticipantsList();
        }
    }
}

function participantsSignature() {
    const list = state.participants || [];
    return list
        .map(p => `${p.id}|${p.name || ''}|${p.joinedAt || ''}`)
        .join(';') + `#${state.participantId || ''}`;
}

function updateParticipantsList() {
    const listEl = el('participantsList');
    if (!listEl) return;

    /* Опрос идёт каждые 2 секунды, а данные обычно те же.
       Без этой проверки список перерисовывался бы и мигал. */
    const signature = participantsSignature();
    if (signature === state.participantsSig && listEl.childElementCount > 0) {
        return;
    }
    state.participantsSig = signature;

    const previousIds = state.participantsPrevIds || [];
    const isNewcomer = (id) => previousIds.indexOf(id) === -1;
    state.participantsPrevIds = (state.participants || []).map(p => p.id);
    let newIndex = 0;

    listEl.replaceChildren();

    if (!state.participants || state.participants.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'seats-empty';
        empty.textContent = 'Пока пусто — ждём гостей…';
        listEl.appendChild(empty);
        return;
    }

    state.participants.forEach((p) => {
        const row = document.createElement('div');
        if (isNewcomer(p.id)) {
            row.className = 'seat-plaque plaque-new';
            row.style.setProperty('--i', newIndex++);
        } else {
            row.className = 'seat-plaque';
        }

        const bulb = document.createElement('span');
        bulb.className = 'plaque-bulb';

        const name = document.createElement('span');
        name.className = 'plaque-name';
        name.textContent = (p.name || 'Участник') + (p.id === state.participantId ? ' — это вы' : '');

        row.append(bulb, name);

        if (p.host) {
            const badge = document.createElement('span');
            badge.className = 'plaque-host';
            badge.textContent = 'хост';
            row.appendChild(badge);
        }

        const time = formatJoinedAt(p.joinedAt);
        if (time) {
            const t = document.createElement('span');
            t.className = 'plaque-time';
            t.textContent = time;
            row.appendChild(t);
        }

        /* Исключать может только хост и только других участников. */
        if (state.isHost && !p.host && p.id !== state.participantId) {
            const kick = document.createElement('button');
            kick.type = 'button';
            kick.className = 'plaque-kick';
            kick.textContent = 'Исключить';
            kick.title = 'Исключить участника из комнаты';
            kick.addEventListener('click', () => kickParticipant(p.id, p.name));
            row.appendChild(kick);
        }

        listEl.appendChild(row);
    });

    if (typeof window.renderSeatMap === 'function') {
        window.renderSeatMap();
    }
}

/* Хост исключает участника: DELETE .../participants/{id}/host */
async function kickParticipant(participantId, participantName) {
    if (!state.isHost || !participantId) return;

    try {
        const url = `${state.sessionServiceUrl}/${encodeURIComponent(state.sessionId)}`
            + `/participants/${encodeURIComponent(participantId)}/host`
            + `?requesterId=${encodeURIComponent(state.participantId)}`
            + (state.hostToken ? `&hostToken=${encodeURIComponent(state.hostToken)}` : '');
        const response = await fetchWithTimeout(url, { method: 'DELETE' }, 10000);

        if (response.status === 403) {
            showToast('Исключать участников может только хост');
            return;
        }
        if (response.status === 400) {
            showToast('Нельзя исключить самого себя');
            return;
        }
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const data = await response.json().catch(() => ({}));
        state.participants = Array.isArray(data.participants) ? data.participants : state.participants;
        state.participantsSig = null;
        updateParticipantsList();
        showToast(`${participantName || 'Участник'} исключён из комнаты`);
    } catch (err) {
        console.warn('kickParticipant error:', err);
        showToast('Не удалось исключить участника');
    }
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
            preloadVotingCatalog().catch(error => {
                console.warn('preloadVotingCatalog error:', error);
            });

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/start`, () => {
                beginVotingStart();
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
                        applyMovieIndexUpdate(payload.movieIndex);
                    }
                } catch (e) {
                    console.warn('index subscription parse error', e);
                }
            });

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/participants`, () => {
                loadParticipants();
            });

            /* Участник покинул комнату: reason = LEFT | KICKED | STALE */
            state.stompClient.subscribe(`/topic/session/${state.sessionId}/left`, (msg) => {
                try {
                    const p = JSON.parse(msg.body);
                    if (!p) return;

                    if (p.participantId && p.participantId === state.participantId) {
                        handleRemovedFromRoom(p.reason || 'KICKED');
                        return;
                    }

                    if (p.participantName) {
                        const reason = p.reason === 'KICKED'
                            ? 'исключён из комнаты'
                            : (p.reason === 'STALE' ? 'отключился' : 'вышел из комнаты');
                        showToast(`${p.participantName} ${reason}`);
                    }
                    loadParticipants();
                } catch (error) {
                    console.warn('leave event handling error:', error);
                }
            });

            state.stompClient.subscribe(`/topic/session/${state.sessionId}/errors`, (msg) => {
                try {
                    const p = JSON.parse(msg.body);
                    if (p.participantId === state.participantId) unlockVoting(p.message);
                } catch {}
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

function applyMovieIndexUpdate(movieIndex) {
    state.currentMovieIndex = movieIndex;
    state.participantVotes = {};
    state.voted = false;
    state.votedDecision = null;
    state.roundResolved = false;
    state.movieFetchIdx = null;
    setVotingNotice('Голосование активно');
    renderStatusRow();
    loadCurrentMovie();
}

function handleStartMessage() {
    if (state.votingStarted) {
        return;
    }

    state.votingStarted = true;
    showToast('Голосование начато!');
    initVoting().catch(err => {
        console.error('initVoting error', err);
        showToast('Ошибка запуска голосования');
    });
}

function preloadVotingCatalog() {
    if (state.votingWarmupPromise) {
        return state.votingWarmupPromise;
    }

    state.votingWarmupPromise = fetchWithTimeout(`${state.votingServiceUrl}/movies`, {}, 45000)
        .then(resp => {
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}`);
            }
            return resp.json();
        })
        .then(movies => {
            if (Array.isArray(movies) && movies.length > 0) {
                state.movies = movies;
            }
            return state.movies;
        })
        .catch(error => {
            state.votingWarmupPromise = null;
            throw error;
        });

    return state.votingWarmupPromise;
}

function ensureVotingStarted() {
    if (state.votingStarted || state.votingInitPromise) {
        return;
    }

    state.votingInitPromise = initVotingReliable()
        .then(() => {
            state.votingInitAttempts = 0;
            state.votingInitPromise = null;
            state.startRequestSent = false;
            setStartButtonLoading(false);
        })
        .catch(err => {
            state.votingInitPromise = null;
            state.votingInitAttempts += 1;
            console.error('initVoting error', err);

            if (state.votingInitAttempts >= 8) {
                state.startRequestSent = false;
                setStartButtonLoading(false);
                showToast('Сервис голосования пока недоступен');
                return;
            }

            showToast('Сервис голосования просыпается...');
            setTimeout(() => {
                if (!state.votingStarted) {
                    ensureVotingStarted();
                }
            }, 4000);
        });
}

function beginVotingStart() {
    if (state.votingStarted || state.votingInitPromise) {
        return;
    }

    showToast('Голосование начато!');
    ensureVotingStarted();
}

function handleVoteUpdate(message) {
    try {
        const vote = JSON.parse(message.body);
        if (!vote || !vote.participantId) return;

        const current = state.movies[state.currentMovieIndex];
        if (!current || vote.movieId !== current.id) return;

        state.participantVotes[vote.participantId] = vote.decision;
        renderStatusRow();

        if (state.voted && vote.totalParticipants) {
            setVotingNotice(`Ждём остальных: проголосовали ${vote.votedCount} из ${vote.totalParticipants}`);
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

async function startVoting() {
    if (!state.isHost) {
        showToast('Только хост может начать голосование');
        return;
    }
    if ((state.participants || []).length < 2) {
        showToast('Нужно минимум 2 участника');
        return;
    }

    if (state.startRequestSent || state.votingStarted) {
        return;
    }

    try {
        state.startRequestSent = true;
        setStartButtonLoading(true);
        warmupBackends().catch(() => {});
        await markSessionVotingStarted();

        if (state.connected && state.stompClient) {
            state.stompClient.send('/app/start-voting', {}, JSON.stringify({
                sessionId: state.sessionId,
                by: state.participantName || state.participantId || 'host'
            }));
        }

        beginVotingStart();
    } catch (e) {
        console.warn('startVoting failed', e);
        state.startRequestSent = false;
        setStartButtonLoading(false);
        showToast('Не удалось запустить голосование');
    }
}

async function initVoting() {
    try {
        await initVotingReliable();
    } catch (err) {
        console.error('initVoting err', err);
        showToast(err.message || 'Ошибка загрузки фильмов');
    }
}

async function initVotingReliable() {
    const warmedMovies = await preloadVotingCatalog();
    if (Array.isArray(warmedMovies) && warmedMovies.length > 0) {
        state.movies = warmedMovies;
    }

    if (!Array.isArray(state.movies) || state.movies.length === 0) {
        const response = await fetchWithTimeout(`${state.votingServiceUrl}/movies`, {}, 45000);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        state.movies = await response.json();
    }

    if (!Array.isArray(state.movies) || state.movies.length === 0) {
        throw new Error('No movies available');
    }

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
}

function vote(decision) {
    if (state.voted) return;

    if (!state.votingStarted) {
        showToast('Сначала нажмите "Начать выбор"');
        return;
    }
    if (!state.connected || !state.stompClient) {
        showToast('Нет соединения с сервером, подождите секунду');
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
    setVoteButtonsDisabled(true);
    setVotingNotice('Ждём остальных участников…');
    renderStatusRow();
    showVoteStamp(decision);

    try {
        state.stompClient.send('/app/vote', {}, JSON.stringify({
            sessionId: state.sessionId,
            participantId: state.participantId,
            movieId: movie.id,
            decision: decision
        }));
    } catch (e) {
        console.error('Failed to send vote:', e);
        unlockVoting('Не удалось отправить голос');
    }
}

function renderMovie(movie) {
    const movieTitle = el('movieTitle');
    const movieMeta = el('movieMeta');
    const moviePoster = el('moviePoster');
    const posterWrap = el('posterWrap');
    const yesBtn = el('yesBtn');
    const noBtn = el('noBtn');

    if (movieTitle) movieTitle.textContent = movie.title || 'Без названия';
    if (movieMeta) {
        movieMeta.textContent = movie.overview || 'Описание отсутствует';
        movieMeta.scrollTop = 0;
    }
    if (moviePoster) {
        moviePoster.src = movie.posterPath
            ? `https://image.tmdb.org/t/p/w500${movie.posterPath}`
            : 'https://via.placeholder.com/340x510?text=No+Image';
        moviePoster.alt = movie.title || 'Poster';
    }
    if (posterWrap) {
        posterWrap.classList.remove('flip');
        void posterWrap.offsetWidth;
        posterWrap.classList.add('flip');
    }
    removeVoteStamp();
    if (yesBtn) yesBtn.disabled = false;
    if (noBtn) noBtn.disabled = false;

    if (typeof window.updateProgress === 'function') window.updateProgress();
    if (typeof window.applyMovieExtras === 'function') window.applyMovieExtras();
}

function showMovieLoading() {
    const movieTitle = el('movieTitle');
    if (movieTitle) movieTitle.textContent = 'Загружаем следующий фильм…';
}

function showMovieMissing() {
    const movieTitle = el('movieTitle');
    const moviePoster = el('moviePoster');
    if (movieTitle) movieTitle.textContent = 'Фильм не найден';
    if (moviePoster) moviePoster.src = '';
}

function loadCurrentMovie() {
    const idx = state.currentMovieIndex;
    const movie = state.movies && state.movies[idx];

    if (movie) {
        renderMovie(movie);
        return;
    }

    /* Индекс за пределами загруженного каталога — догружаем точечно */
    if (idx === null || idx === undefined || idx < 0) {
        showMovieMissing();
        return;
    }
    if (state.movieFetchIdx === idx) return; /* этот индекс уже грузим */
    state.movieFetchIdx = idx;
    showMovieLoading();

    fetchWithTimeout(`${state.votingServiceUrl}/movies/${idx}`, {}, 20000)
        .then(r => {
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            return r.json();
        })
        .then(m => {
            if (state.currentMovieIndex !== idx) return; /* фильм уже сменился */
            if (!m) throw new Error('Пустой ответ');
            if (!Array.isArray(state.movies)) state.movies = [];
            state.movies[idx] = m;
            state.movieFetchIdx = null;
            renderMovie(m);
        })
        .catch(err => {
            console.warn('loadCurrentMovie lazy load error:', err);
            if (state.currentMovieIndex === idx) {
                state.movieFetchIdx = null;
                showMovieMissing();
            }
        });
}

function showVoteStamp(decision) {
    const wrap = el('posterWrap');
    if (!wrap) return;
    removeVoteStamp();
    const stamp = document.createElement('div');
    stamp.className = 'stamp ' + (decision === 'LIKE' ? 'stamp-like' : 'stamp-dislike');
    const label = document.createElement('span');
    label.textContent = decision === 'LIKE' ? 'Нравится!' : 'Не нравится!';
    stamp.appendChild(label);
    wrap.appendChild(stamp);
}

function removeVoteStamp() {
    const wrap = el('posterWrap');
    if (!wrap) return;
    wrap.querySelectorAll('.stamp').forEach(n => n.remove());
}

function showMatchScreen(match) {
    stopParticipantsPolling();
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

function setVoteButtonsDisabled(disabled) {
    const yesBtn = el('yesBtn');
    const noBtn = el('noBtn');
    if (yesBtn) yesBtn.disabled = disabled;
    if (noBtn) noBtn.disabled = disabled;
}

function setVotingNotice(text) {
    const notice = el('votingNotice');
    if (notice) notice.textContent = text;
}

function unlockVoting(message) {
    state.voted = false;
    state.votedDecision = null;
    delete state.participantVotes[state.participantId];
    setVoteButtonsDisabled(false);
    setVotingNotice('Голосование активно');
    removeVoteStamp();
    renderStatusRow();
    if (message) showToast(message);
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
    setStartButtonLoading(false);
    state.startRequestSent = false;
    state.votingInitAttempts = 0;
    preloadVotingCatalog().catch(error => {
        console.warn('waiting-screen preloadVotingCatalog error:', error);
    });

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
    startParticipantsPolling();
    setStartButtonLoading(false);
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

/* ── Выход из комнаты ──
   Перезагрузка и закрытие вкладки больше не удаляют участника:
   за это отвечает heartbeat. DELETE шлётся только при явном выходе. */
function sendLeaveBeacon() {
    if (state.leaveSent) return;
    if (!state.sessionId || !state.participantId) return;
    sendBeaconSafe(state.sessionId, state.participantId);
    state.leaveSent = true;
}

function sendBeaconSafe(sessionId, participantId) {
    const url = `${state.sessionServiceUrl}/${encodeURIComponent(sessionId)}/participants/${encodeURIComponent(participantId)}`;
    try {
        fetch(url, { method: 'DELETE', keepalive: true }).catch(() => {});
    } catch (e) { /* ignore */ }
}

/* ── Heartbeat ──
   Пока вкладка открыта, каждые 5 секунд отмечаемся живыми. Сервер
   удаляет участников, не подававших признаков жизни дольше 25 секунд. */
function startHeartbeat() {
    stopHeartbeat();
    if (!state.sessionId || !state.participantId) return;

    const tick = () => {
        if (!state.sessionId || !state.participantId || document.hidden) return;
        fetchWithTimeout(
            `${state.sessionServiceUrl}/${encodeURIComponent(state.sessionId)}/heartbeat`,
            {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ participantId: state.participantId })
            },
            6000
        ).then(resp => {
            if (resp.status === 404) {
                handleRemovedFromRoom();
            }
        }).catch(() => {});
    };

    tick();
    state.heartbeatId = setInterval(tick, 5000);
}

function stopHeartbeat() {
    if (state.heartbeatId) {
        clearInterval(state.heartbeatId);
        state.heartbeatId = null;
    }
}

/* Участника удалили: кикнул хост, истёк таймаут или комната закрыта. */
function handleRemovedFromRoom(reason) {
    if (state.kickedOut) return;
    state.kickedOut = true;

    stopHeartbeat();
    stopParticipantsPolling();

    if (state.stompClient && state.connected) {
        try { state.stompClient.disconnect(); } catch {}
        state.connected = false;
    }

    const text = reason === 'KICKED'
        ? 'Хост исключил вас из комнаты'
        : 'Вы больше не в комнате — сессия завершена или истекла';

    clearParticipantState();
    state.sessionId = null;
    state.sessionCode = null;
    state.participantId = null;
    state.participantName = null;
    state.votingStarted = false;
    state.voted = false;

    showWelcomeScreen();
    showToast(text);
}

/* Возврат после перезагрузки: участник остался на бэкенде, поэтому
   входим под сохранённым токеном. Если голосование уже идёт, сервер
   пустит только своего — чужой токен получит VOTING_STARTED. */
async function restoreAfterReload() {
    const sessionId = state.sessionId;
    const token = getSavedToken();
    const name = state.participantName || getSavedNickname();
    if (!sessionId || !token || !name) {
        return false;
    }

    try {
        const participant = await registerParticipant(sessionId, state.isHost, name, token);
        state.leaveSent = false;
        state.kickedOut = false;
        enterRoom({
            sessionId: sessionId,
            code: participant.code,
            currentMovieIndex: participant.currentMovieIndex
        }, participant, participant.host === true, null);
        if (participant.votingStarted === true) {
            beginVotingStart();
        }
        return true;
    } catch (e) {
        console.warn('restoreAfterReload failed:', e);
        clearParticipantSession();
        return false;
    }
}

function exitSession() {
    stopHeartbeat();
    stopParticipantsPolling();

    if (state.stompClient && state.connected) {
        try {
            state.stompClient.disconnect();
        } catch {}
        state.connected = false;
    }

    /* Сообщаем о выходе ДО очистки состояния */
    sendLeaveBeacon();

    state.sessionId = null;
    state.sessionCode = null;
    state.participantId = null;
    state.participantName = null;
    state.votingStarted = false;
    state.voted = false;
    state.votedDecision = null;
    state.participantVotes = {};
    state.roundResolved = false;
    state.startRequestSent = false;
    state.votingWarmupPromise = null;
    state.votingInitPromise = null;
    state.votingInitAttempts = 0;
    state.movieFetchIdx = null;
    state.kickedOut = false;
    setStartButtonLoading(false);

    clearParticipantState();

    showWelcomeScreen();
}

function goBackToWelcome() {
    exitSession();
}

function playConfetti() {
    const colors = ['#f3d894', '#e0b45c', '#b98a3a', '#f6e7c6', '#a4263c'];
    const count = 60;
    for (let i = 0; i < count; i++) {
        const c = document.createElement('div');
        const w = 5 + Math.random() * 8;
        c.style.position = 'fixed';
        c.style.top = '-12px';
        c.style.left = Math.random() * 100 + '%';
        c.style.width = w + 'px';
        c.style.height = (w * (1 + Math.random())) + 'px';
        c.style.background = colors[Math.floor(Math.random() * colors.length)];
        c.style.opacity = 0.95;
        c.style.borderRadius = '1px';
        c.style.zIndex = 9999;
        c.style.pointerEvents = 'none';
        c.style.transition = 'transform 2.8s cubic-bezier(.25,.6,.4,1), top 2.8s ease-in, opacity 2.8s ease';
        document.body.appendChild(c);

        setTimeout(() => {
            c.style.top = (100 + Math.random() * 30) + 'vh';
            c.style.transform = `translateX(${Math.random() * 50 - 25}vw) rotate(${Math.random() * 900 - 450}deg)`;
            c.style.opacity = 0;
        }, 50);

        setTimeout(() => c.remove(), 3000);
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    initNicknameModal();

    const savedPid = localStorage.getItem('participantId');
    const savedSid = localStorage.getItem('sessionId');
    const savedParticipantName = localStorage.getItem('participantName');
    const savedIsHost = localStorage.getItem('isHost') === 'true';

    /* Личность восстанавливаем только в той вкладке, которая реально была
       в этой комнате. Новая вкладка или чужая ссылка не должны давать
       права хоста и подставлять чужое имя в список. */
    let tabSession = null;
    try { tabSession = sessionStorage.getItem('yakTabSession'); } catch (e) {}

    if (savedPid && savedSid && tabSession === savedSid) {
        state.participantId = savedPid;
        state.sessionId = savedSid;
        state.participantName = savedParticipantName;
        state.isHost = savedIsHost;
        state.participantToken = getSavedToken() || null;
        state.hostToken = localStorage.getItem('hostToken') || null;
    }

    switchTab('join');
    warmupBackends().catch(() => {});

    const restored = await restoreAfterReload();
    if (!restored) {
        checkUrlParams();
    }
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

    row.replaceChildren();

    if (!state.participants || state.participants.length === 0) {
        row.textContent = 'Статус голосов обновляется...';
        return;
    }

    state.participants.forEach(p => {
        const div = document.createElement('div');
        const voted = Boolean(state.participantVotes[p.id]);
        div.className = 'status-item' + (voted ? ' voted' : '');

        const dot = document.createElement('span');
        dot.className = 'status-dot';

        const label = document.createElement('span');
        label.textContent = (p.id === state.participantId ? 'Вы — ' : '') + (voted ? 'голос принят' : 'думает…');

        div.append(dot, label);
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
window.joinSessionDirect = joinSessionDirect;