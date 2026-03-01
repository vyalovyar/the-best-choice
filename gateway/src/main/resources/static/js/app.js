// ===== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ =====
function getDefaultWsUrl() {
    const protocol = window.location.protocol;
    const host = window.location.host;
    return `${protocol}//${host}/ws`;
}

// ===== СОСТОЯНИЕ ПРИЛОЖЕНИЯ =====
const state = {
    // Параметры сессии
    sessionId: null,
    participantId: null,
    sessionCode: null,
    participantIds: [], // Список всех участников в сессии

    // Состояние голосования
    currentMovieIndex: 0,
    movies: [],
    voted: false,
    votedDecision: null,

    // Статусы участников
    participantVotes: {}, // объект для отслеживания голосов по participantId

    // WebSocket
    stompClient: null,
    connected: false,

    // Параметры бэкенда
    // На production используем gateway, на локалке - напрямую
    sessionServiceUrl: window.BACKEND_SESSION_URL || `${window.location.origin}/api/sessions`,
    votingServiceUrl: window.BACKEND_VOTING_URL || `${window.location.origin}/api/voting`,
    wsUrl: window.BACKEND_WS_URL || getDefaultWsUrl(),
};

// ===== ИНИЦИАЛИЗАЦИЯ =====
document.addEventListener('DOMContentLoaded', () => {
    checkUrlParams();
});

// ===== УПРАВЛЕНИЕ ВКЛАДКАМИ =====
function switchTab(tabName) {
    // Скрыть все вкладки
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.remove('active');
    });

    // Показать выбранную вкладку
    document.getElementById(tabName + 'Tab').classList.add('active');
    event.target.classList.add('active');

    // Очистить ошибки
    document.getElementById(tabName + 'Error').classList.add('hidden');
}

// ===== ПРОВЕРКА URL ПАРАМЕТРОВ =====
function checkUrlParams() {
    const params = new URLSearchParams(window.location.search);
    const sessionId = params.get('session');

    if (sessionId) {
        // Автоматически присоединиться к сессии
        state.sessionId = sessionId;
        joinSessionDirect();
    }
}

// ===== СОЗДАНИЕ СЕССИИ =====
async function createSession() {
    try {
        const btn = event.target;
        btn.disabled = true;
        btn.textContent = 'Создание...';

        const response = await fetchWithTimeout(state.sessionServiceUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
        }, 10000);

        if (!response.ok) {
            throw new Error(`Ошибка создания сессии (${response.status})`);
        }

        const data = await response.json();
        state.sessionId = data.sessionId;
        state.sessionCode = data.code;
        state.currentMovieIndex = parseInt(data.currentMovieIndex) || 0;
        state.participantId = generateParticipantId();

        // Сохраняем в localStorage
        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);

        // Переходим на экран ожидания
        showWaitingScreen();

        // Подключаемся к WebSocket
        connectWebSocket();
    } catch (error) {
        console.error('Ошибка:', error);
        document.getElementById('createError').textContent = error.message;
        document.getElementById('createError').classList.remove('hidden');
        event.target.disabled = false;
        event.target.textContent = 'Создать сессию';
    }
}



// ===== ПРИСОЕДИНЕНИЕ К СЕССИИ =====
async function joinSession() {
    try {
        const link = document.getElementById('sessionLink').value.trim();

        if (!link) {
            throw new Error('Введите ссылку сессии');
        }

        // Парсим sessionId из ссылки
        const url = new URL(link);
        const sessionId = url.searchParams.get('session');

        if (!sessionId) {
            throw new Error('Неверный формат ссылки. Должна быть /session=...');
        }

        state.sessionId = sessionId;
        state.participantId = generateParticipantId();

        // Сохраняем в localStorage
        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);

        // Определяем sessionCode из базы
        await fetchSessionCode(sessionId);

        // Переходим на экран голосования
        await initVoting();
    } catch (error) {
        console.error('Ошибка:', error);
        document.getElementById('joinError').textContent = error.message;
        document.getElementById('joinError').classList.remove('hidden');
    }
}

// ===== ПРЯМОЕ ПРИСОЕДИНЕНИЕ =====
async function joinSessionDirect() {
    try {
        state.participantId = generateParticipantId();
        localStorage.setItem('participantId', state.participantId);
        localStorage.setItem('sessionId', state.sessionId);

        await fetchSessionCode(state.sessionId);
        await initVoting();
    } catch (error) {
        console.error('Ошибка при присоединении:', error);
        document.getElementById('joinError').textContent = 'Не удалось присоединиться к сессии';
        document.getElementById('joinError').classList.remove('hidden');
        showWelcomeScreen();
    }
}

// ===== ПОЛУЧЕНИЕ КОДА СЕССИИ =====
async function fetchSessionCode(sessionId) {
    try {
        // Пытаемся получить информацию о сессии по её ID
        // Заметим: серверной может потребоваться добавить новый endpoint для получения по sessionId
        // Пока используем код из sessionId
        const response = await fetch(`${state.sessionServiceUrl}/${sessionId}`, {
            method: 'GET',
        });

        if (response.ok) {
            const data = await response.json();
            state.sessionCode = data.code;
            state.currentMovieIndex = parseInt(data.currentMovieIndex) || 0;
            console.log('Получен код сессии:', state.sessionCode, 'Индекс фильма:', state.currentMovieIndex);
        } else {
            console.warn('Не удалось получить информацию о сессии');
            // Используем первые несколько символов sessionId как fallback
            state.sessionCode = sessionId.substring(0, 6).toUpperCase();
        }
    } catch (error) {
        console.warn('Не удалось получить код сессии:', error);
        // Используем первые несколько символов sessionId как fallback
        state.sessionCode = sessionId.substring(0, 6).toUpperCase();
    }
}

// ===== ИНИЦИАЛИЗАЦИЯ ГОЛОСОВАНИЯ =====
async function initVoting() {
    try {
        console.log('[initVoting] 🎬 Начало инициализации голосования');
        
        // Загружаем фильмы
        console.log('[initVoting] 🍿 Загрузка фильмов...');
        const response = await fetch(`${state.votingServiceUrl}/movies`);

        if (!response.ok) {
            throw new Error(`Ошибка загрузки фильмов (${response.status})`);
        }

        state.movies = await response.json();
        console.log('[initVoting] ✅ Фильмы загруженны:', state.movies.length);

        if (state.movies.length === 0) {
            throw new Error('Нет доступных фильмов');
        }

        // Подключаемся к WebSocket
        console.log('[initVoting] 🔌 Подключение WebSocket...');
        await connectWebSocketWithTimeout(5000);
        console.log('[initVoting] ✅ WebSocket подключен');

        // Загружаем первый фильм
        console.log('[initVoting] 🎞️ Загрузка первого фильма...');
        loadCurrentMovie();
        console.log('[initVoting] ✅ Первый фильм загружен');

        // Переходим на экран голосования
        console.log('[initVoting] 📺 Переход на экран голосования...');
        showVotingScreen();
        console.log('[initVoting] ✅ Экран голосования показан');
        
    } catch (error) {
        console.error('[initVoting] ❌ Ошибка:', error);
        const errorMsg = error.message || 'Неизвестная ошибка инициализации голосования';
        document.getElementById('joinError').textContent = errorMsg;
        document.getElementById('joinError').classList.remove('hidden');
    }
}

// ===== WEBSOCKET =====
function connectWebSocket() {
    console.log('[connectWebSocket] 🔗 Подключение к WebSocket...');
    const socket = new SockJS(state.wsUrl);
    state.stompClient = Stomp.over(socket);

    state.stompClient.connect(
        {},
        (frame) => {
            console.log('[connectWebSocket] ✅ WebSocket подключен:', frame);
            state.connected = true;

            // Подписываемся на обновления голосов
            state.stompClient.subscribe(
                `/topic/session/${state.sessionId}/votes`,
                handleVoteUpdate
            );
            console.log('[connectWebSocket] 📨 Подписка на голоса');

            // Подписываемся на совпадения
            state.stompClient.subscribe(
                `/topic/session/${state.sessionId}/match`,
                handleMatchMessage
            );
            console.log('[connectWebSocket] 📨 Подписка на совпадения');
        },
        (error) => {
            console.error('[connectWebSocket] ❌ Ошибка подключения:', error);
            state.connected = false;
            // Пытаемся переподключиться через 3 секунды
            setTimeout(connectWebSocket, 3000);
        }
    );
}

// ===== WEBSOCKET С ТАЙМАУТОМ =====
function connectWebSocketWithTimeout(timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
        console.log('[connectWebSocketWithTimeout] ⏱️ Подключение WebSocket с таймаутом:', timeoutMs);
        
        const timeout = setTimeout(() => {
            console.error('[connectWebSocketWithTimeout] ⏱️ Таймаут WebSocket!');
            if (state.stompClient) {
                state.stompClient.disconnect(() => {
                    console.log('[connectWebSocketWithTimeout] WebSocket отключен по таймауту');
                });
            }
            reject(new Error(`WebSocket таймаут (${timeoutMs}мс)`));
        }, timeoutMs);

        const socket = new SockJS(state.wsUrl);
        state.stompClient = Stomp.over(socket);

        state.stompClient.connect(
            {},
            (frame) => {
                clearTimeout(timeout);
                console.log('[connectWebSocketWithTimeout] ✅ WebSocket успешно подключен');
                state.connected = true;

                // Подписываемся на обновления голосов
                state.stompClient.subscribe(
                    `/topic/session/${state.sessionId}/votes`,
                    handleVoteUpdate
                );

                // Подписываемся на совпадения
                state.stompClient.subscribe(
                    `/topic/session/${state.sessionId}/match`,
                    handleMatchMessage
                );

                resolve();
            },
            (error) => {
                clearTimeout(timeout);
                console.error('[connectWebSocketWithTimeout] ❌ Ошибка подключения:', error);
                state.connected = false;
                reject(error);
            }
        );
    });
}

// ===== ОБРАБОТКА ОБНОВЛЕНИЯ ГОЛОСА =====
function handleVoteUpdate(message) {
    const vote = JSON.parse(message.body);
    console.log('Получен голос:', vote);

    // Добавляем участника в список, если его еще там нет
    if (!state.participantIds.includes(vote.participantId)) {
        state.participantIds.push(vote.participantId);
    }

    // Сохраняем голос участника по текущему фильму
    state.participantVotes[vote.participantId] = vote.decision;

    // Обновляем визуальный статус
    if (vote.participantId === state.participantId) {
        updateParticipantStatus(1, vote.decision);
    } else {
        updateParticipantStatus(2, vote.decision);
    }

    // Если оба участника проголосовали - переходим к следующему фильму
    if (Object.keys(state.participantVotes).length >= 2) {
        moveToNextMovie();
    }
}

// ===== ОБРАБОТКА СОВПАДЕНИЯ =====
function handleMatchMessage(message) {
    const match = JSON.parse(message.body);
    console.log('Совпадение:', match);

    // Отключаем кнопки голосования
    document.getElementById('yesBtn').disabled = true;
    document.getElementById('noBtn').disabled = true;

    // Ждем 1 секунду перед показом экрана совпадения
    setTimeout(() => {
        showMatchScreen(match);
        playConfetti();
    }, 1000);
}

// ===== ГОЛОСОВАНИЕ =====
async function vote(decision) {
    if (state.voted) {
        return; // Уже проголосовали за этот фильм
    }

    const currentMovie = state.movies[state.currentMovieIndex];

    try {
        // Визуальный обратный связь
        state.voted = true;
        state.votedDecision = decision;

        // Отключаем кнопки
        document.getElementById('yesBtn').disabled = true;
        document.getElementById('noBtn').disabled = true;

        // Визуально показываем выбор
        if (decision === 'LIKE') {
            document.getElementById('yesBtn').classList.add('voted');
            updateParticipantStatus(1, decision);
        } else {
            document.getElementById('noBtn').classList.add('voted');
            updateParticipantStatus(1, decision);
        }

        // Отправляем голос через WebSocket
        if (state.connected && state.stompClient) {
            state.stompClient.send('/app/vote', {}, JSON.stringify({
                sessionId: state.sessionId,
                participantId: state.participantId,
                movieId: currentMovie.id,
                decision: decision,
            }));
        } else {
            throw new Error('WebSocket не подключен');
        }
    } catch (error) {
        console.error('Ошибка голосования:', error);
        state.voted = false;
        document.getElementById('yesBtn').disabled = false;
        document.getElementById('noBtn').disabled = false;
    }
}

// ===== ПЕРЕХОД К СЛЕДУЮЩЕМУ ФИЛЬМУ =====
function moveToNextMovie() {
    // Ждем 1-2 секунды перед переходом
    setTimeout(() => {
        state.currentMovieIndex = (state.currentMovieIndex + 1) % state.movies.length;
        state.voted = false;
        state.votedDecision = null;
        state.participantVotes = {}; // Очищаем голоса для следующего фильма

        // отправляем обновление индекса на бэкенд
        if (state.connected && state.stompClient) {
            state.stompClient.send('/app/update-movie-index', {}, JSON.stringify({
                sessionId: state.sessionId,
                movieIndex: state.currentMovieIndex,
            }));
        }

        // Проверяем, не загружаем ли мы первый фильм снова
        if (state.currentMovieIndex === 0) {
            console.log('Перезагружаем список фильмов...');
            // Можно обновить список фильмов, но пока просто циклируем
        }

        loadCurrentMovie();
    }, 2000);
}

// ===== ЗАГРУЗКА ТЕКУЩЕГО ФИЛЬМА =====
function loadCurrentMovie() {
    const movie = state.movies[state.currentMovieIndex];

    if (!movie) {
        console.error('Фильм не найден');
        return;
    }

    // Обновляем UI
    document.getElementById('movieTitle').textContent = movie.title;
    document.getElementById('movieCounter').textContent = `Фильм ${state.currentMovieIndex + 1} из ${state.movies.length}`;

    // Загружаем постер
    const imageUrl = movie.posterPath
        ? `https://image.tmdb.org/t/p/w500${movie.posterPath}`
        : 'https://via.placeholder.com/300x450?text=No+Image';

    document.getElementById('moviePoster').src = imageUrl;
    document.getElementById('moviePoster').alt = movie.title;

    // Сбрасываем состояние кнопок
    document.getElementById('yesBtn').classList.remove('voted');
    document.getElementById('noBtn').classList.remove('voted');
    document.getElementById('yesBtn').disabled = false;
    document.getElementById('noBtn').disabled = false;

    // Сбрасываем статусы участников
    updateParticipantStatus(1, null);
    updateParticipantStatus(2, null);
}

// ===== ОБНОВЛЕНИЕ СТАТУСА УЧАСТНИКА =====
function updateParticipantStatus(participantNum, decision) {
    const statusEl = document.getElementById(`participant${participantNum}Status`);

    if (decision === 'LIKE') {
        statusEl.textContent = '✅';
        statusEl.style.color = '#11998e';
    } else if (decision === 'DISLIKE') {
        statusEl.textContent = '❌';
        statusEl.style.color = '#eb3349';
    } else {
        statusEl.textContent = '⏳';
        statusEl.style.color = '#999';
    }
}

// ===== ПОКАЗ ЭКРАНА СОВПАДЕНИЯ =====
function showMatchScreen(match) {
    const imageUrl = match.posterPath
        ? `https://image.tmdb.org/t/p/w500${match.posterPath}`
        : 'https://via.placeholder.com/250x375?text=No+Image';

    document.getElementById('matchPoster').src = imageUrl;
    document.getElementById('matchTitle').textContent = match.movieTitle;

    showScreen('matchScreen');
}

// ===== СЛЕДУЮЩЕЕ СОВПАДЕНИЕ =====
function nextMatch() {
    hideAndResetVoting();
    showVotingScreen();
}

// ===== ВЫХОД ИЗ СЕССИИ =====
function exitSession() {
    // Отключаемся от WebSocket
    if (state.stompClient && state.connected) {
        state.stompClient.disconnect(() => {
            console.log('Отключены от WebSocket');
        });
    }

    // Очищаем состояние
    state.sessionId = null;
    state.participantId = null;
    localStorage.removeItem('participantId');
    localStorage.removeItem('sessionId');

    // Возвращаемся на главный экран
    showWelcomeScreen();
}

// ===== ВОЗВРАТ НА ГЛАВНУЮ =====
function goBackToWelcome() {
    if (state.stompClient && state.connected) {
        state.stompClient.disconnect(() => {
            console.log('Отключены от WebSocket');
        });
    }

    state.sessionId = null;
    state.participantId = null;
    localStorage.removeItem('participantId');
    localStorage.removeItem('sessionId');

    showWelcomeScreen();
}

// ===== УПРАВЛЕНИЕ ЭКРАНАМИ =====
function showScreen(screenId) {
    document.querySelectorAll('.screen').forEach(screen => {
        screen.classList.remove('active');
    });
    document.getElementById(screenId).classList.add('active');
}

function showWelcomeScreen() {
    showScreen('welcomeScreen');
}

function showWaitingScreen() {
    const shareLink = `${window.location.origin}/?session=${state.sessionId}`;
    document.getElementById('inviteLink').value = shareLink;

    const codeElement = document.querySelector('#waitingCode strong');
    if (codeElement) {
        codeElement.textContent = state.sessionCode || state.sessionId.substring(0, 6);
    }

    showScreen('waitingScreen');
}

function showVotingScreen() {
    showScreen('votingScreen');
}

function hideAndResetVoting() {
    // Сбрасываем состояние голосования
    state.voted = false;
    state.votedDecision = null;
    state.participantVotes = {}; // Очищаем голоса

    // Сбрасываем UI
    document.getElementById('yesBtn').classList.remove('voted');
    document.getElementById('noBtn').classList.remove('voted');
    document.getElementById('yesBtn').disabled = false;
    document.getElementById('noBtn').disabled = false;

    updateParticipantStatus(1, null);
    updateParticipantStatus(2, null);
}

// ===== КОПИРОВАНИЕ В БУФЕР ОБМЕНА =====
function copyToClipboard() {
    const link = document.getElementById('inviteLink').value;
    navigator.clipboard.writeText(link).then(() => {
        const btn = event.target;
        const originalText = btn.textContent;
        btn.textContent = 'Скопировано!';
        setTimeout(() => {
            btn.textContent = originalText;
        }, 2000);
    }).catch(err => {
        console.error('Не удалось скопировать:', err);
    });
}

// ===== ЭФФЕКТ КОНФЕТТИ =====
function playConfetti() {
    const confettiCount = 50;
    const colors = ['#667eea', '#764ba2', '#11998e', '#38ef7d', '#eb3349', '#f45c43'];

    for (let i = 0; i < confettiCount; i++) {
        const confetti = document.createElement('div');
        confetti.className = 'confetti';
        confetti.textContent = ['🎬', '🎉', '⭐', '🎊'][Math.floor(Math.random() * 4)];
        confetti.style.left = Math.random() * 100 + '%';
        confetti.style.top = '-10px';
        confetti.style.fontSize = (Math.random() * 20 + 20) + 'px';
        confetti.style.opacity = Math.random() * 0.7 + 0.3;
        confetti.style.animation = `confettifall ${Math.random() * 2 + 2}s ease-in forwards`;

        document.body.appendChild(confetti);

        // Удаляем элемент после анимации
        setTimeout(() => {
            confetti.remove();
        }, 3000);
    }
}

// ===== УТИЛИТЫ =====
function generateParticipantId() {
    return 'participant_' + Math.random().toString(36).substr(2, 9);
}

// ===== FETCH С ТАЙМАУТОМ =====
function fetchWithTimeout(url, options = {}, timeoutMs = 10000) {
    return Promise.race([
        fetch(url, options),
        new Promise((_, reject) =>
            setTimeout(() => reject(new Error(`Запрос истёк (${timeoutMs}мс) - сервис недоступен`)), timeoutMs)
        )
    ]);
}

// ===== ОБРАБОТКА ОШИБОК =====
window.addEventListener('error', (event) => {
    console.error('Глобальная ошибка:', event.error);
});

window.addEventListener('unhandledrejection', (event) => {
    console.error('Необработанное отклонение Promise:', event.reason);
});
