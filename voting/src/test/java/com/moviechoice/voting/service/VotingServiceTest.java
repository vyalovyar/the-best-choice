package com.moviechoice.voting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moviechoice.voting.client.TmdbClient;
import com.moviechoice.voting.dto.MovieResponse;
import com.moviechoice.voting.dto.tmld.TmdbMovieDto;
import com.moviechoice.voting.dto.tmld.TmdbMoviesResponseDto;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.entity.VoteDecision;
import com.moviechoice.voting.repository.MovieRepository;
import com.moviechoice.voting.repository.VoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class VotingServiceTest {

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private VoteRepository voteRepository;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private MovieCacheService movieCacheService;

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private VotingService votingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(votingService, "sessionServiceUrl", "http://session-service");
        ReflectionTestUtils.setField(votingService, "internalToken", "test-internal-token");
    }

    //Тест для проверкает, что метод getMoviesForVote обновляет кэш фильмов, когда репозиторий пустой. Он имитирует ответ от TMDB клиента и проверяет, что фильм сохраняется в репозитории с правильными данными
    @Test
    void getMoviesForVoteRefreshesCacheWhenRepositoryIsEmpty() {
        TmdbMovieDto dto = movieDto(101L, "Interstellar", "Space epic", 8.7, "/poster.jpg");
        TmdbMoviesResponseDto response = new TmdbMoviesResponseDto();
        response.setResults(List.of(dto));
        List<Movie> expectedMovies = List.of(Movie.builder().id(101L).title("Interstellar").build());

        when(movieRepository.count()).thenReturn(0L);
        when(movieRepository.findAll(any(Sort.class))).thenReturn(expectedMovies);

        List<Movie> actualMovies = votingService.loadMovies();

        verify(movieCacheService).refreshMovies();
        verify(movieCacheService).assignMissingPositions();
        assertThat(actualMovies).isEqualTo(expectedMovies);
    }
    //Тест для проверки, что метод getMoviesForVote возвращает кэшированные фильмы, когда кэш свежий. Он имитирует наличие фильма в репозитории с недавним временем кэша и проверяет, что этот фильм возвращается без вызова TMDB клиента или сохранения нового фильма
    @Test
    void getMoviesForVoteReturnsCachedMoviesWhenCacheIsFresh() {
        Movie cachedMovie = Movie.builder()
                .id(1L)
                .title("Inception")
                .overview("Dreams")
                .cacheAt(ZonedDateTime.now())
                .build();

        when(movieRepository.count()).thenReturn(1L);
        when(movieRepository.findAll(any(Sort.class))).thenReturn(List.of(cachedMovie));

        List<Movie> movies = votingService.loadMovies();

        assertThat(movies).containsExactly(cachedMovie);
        verifyNoInteractions(movieCacheService);
    }
    //Тест для проверки, что метод updateMoviesFromTmdb обновляет существующий фильм, если он уже есть в репозитории. Он имитирует ответ от TMDB клиента с новым DTO для существующего фильма и проверяет, что фильм в репозитории обновляется с новыми данными и сохраняется
    @Test
    void updateMoviesFromTmdbUpdatesExistingMovie() {
        Movie existingMovie = Movie.builder()
                .id(42L)
                .title("Old title")
                .overview("Old overview")
                .voteAvg(5.1)
                .posterPath("/old.jpg")
                .cacheAt(ZonedDateTime.now().minusDays(2))
                .build();
        TmdbMovieDto dto = movieDto(42L, "New title", "New overview", 8.4, "/new.jpg");
        TmdbMoviesResponseDto response = new TmdbMoviesResponseDto();
        response.setResults(List.of(dto));

        votingService.updateMoviesFromTmdb();

        verify(movieCacheService).refreshMovies();
        verify(movieCacheService).assignMissingPositions();
    }
    //тест для проверки, что метод updateMoviesFromTmdb останавливается, если TMDB клиент возвращает null. Он имитирует такой ответ и проверяет, что репозиторий фильмов не сохраняет никаких фильмов
    @Test
    void updateMoviesFromTmdbStopsWhenClientReturnsNull() {
        doNothing().when(movieCacheService).refreshMovies();

        votingService.updateMoviesFromTmdb();

        verify(movieCacheService).refreshMovies();
        verify(movieRepository, never()).save(any(Movie.class));
    }
    //Тест для проверки метода createVote, который создает и сохраняет голос для существующего фильма. Он имитирует наличие фильма в репозитории и проверяет, что голос сохраняется с правильными данными. Также есть тест для проверки, что метод createVote выбрасывает исключение, если фильм не существует, и не сохраняет голос в этом случае
    @Test
    void createVotePersistsVoteForExistingMovie() {
        UUID sessionId = UUID.randomUUID();
        Movie movie = Movie.builder()
                .id(10L)
                .title("The Matrix")
                .cacheAt(ZonedDateTime.now())
                .build();
        when(movieRepository.findById(10L)).thenReturn(Optional.of(movie));
        when(voteRepository.save(any(Vote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Vote vote = votingService.createVote(sessionId, "participant-1", 10L, VoteDecision.LIKE);

        ArgumentCaptor<Vote> voteCaptor = ArgumentCaptor.forClass(Vote.class);
        verify(voteRepository).save(voteCaptor.capture());

        Vote savedVote = voteCaptor.getValue();
        assertThat(vote).isSameAs(savedVote);
        assertThat(savedVote.getSessionId()).isEqualTo(sessionId);
        assertThat(savedVote.getParticipantId()).isEqualTo("participant-1");
        assertThat(savedVote.getMovie()).isSameAs(movie);
        assertThat(savedVote.getDecision()).isEqualTo(VoteDecision.LIKE);
        assertThat(savedVote.getCreatedAt()).isNotNull();
    }
    //Тест для проврки метода createVote, который выбрасывает исключение, если фильм не существует. Он имитирует отсутствие фильма в репозитории и проверяет, что выбрасывается RuntimeException с сообщением, содержащим ID фильма.
    @Test
    void createVoteThrowsWhenMovieDoesNotExist() {
        UUID sessionId = UUID.randomUUID();
        when(movieRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> votingService.createVote(sessionId, "participant-1", 404L, VoteDecision.DISLIKE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("404");

        verify(voteRepository, never()).save(any(Vote.class));
    }
    //Тест доя проверки метода getAllVotesInSession, который возвращает все голоса для данной сессии. Он имитирует наличие голосов в репозитории для указанного ID сессии и проверяет, что возвращается правильный список голосов.

    @Test
    void getAllVotesInSessionReturnsRepositoryResult() {
        UUID sessionId = UUID.randomUUID();
        List<Vote> expectedVotes = List.of(Vote.builder().participantId("participant-1").build());
        when(voteRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(expectedVotes);

        List<Vote> actualVotes = votingService.getAllVotesInSession(sessionId);

        assertThat(actualVotes).isEqualTo(expectedVotes);
    }
    //Тут проверяется метод updateMovieIndexInSession, который отправляет POST запрос к сервису сессий для обновления текущего индекса фильма. Он имитирует вызов метода и проверяет, что RestTemplate отправляет запрос с правильным URL и телом запроса. Также есть тест для проверки, что метод не выбрасывает исключение, если RestTemplate выбрасывает ошибку (например, если сервис сессий недоступен).

    @Test
    void updateMovieIndexInSessionPostsPayloadToSessionService() {
        UUID sessionId = UUID.randomUUID();

        votingService.updateMovieIndexInSession(sessionId, 3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                bodyCaptor.capture(),
                eq(Void.class)
        );
        assertThat(bodyCaptor.getValue().getBody()).containsEntry("currentMovieIndex", 3);
        assertThat(bodyCaptor.getValue().getHeaders().getFirst("X-Internal-Token")).isNotBlank();
    }
    //проверка метода updateMovieIndexInSession, который не выбрасывает исключение, если RestTemplate выбрасывает ошибку (например, если сервис сессий недоступен). Он имитирует такую ситуацию и проверяет, что метод выполняется без выброса исключения и что RestTemplate все равно вызывается с правильным URL
    @Test
    void updateMovieIndexInSessionSwallowsRestClientErrors() {
        UUID sessionId = UUID.randomUUID();
        when(restTemplate.postForObject(any(String.class), any(), eq(Void.class)))
                .thenThrow(new RuntimeException("session service unavailable"));

        votingService.updateMovieIndexInSession(sessionId, 5);

        verify(restTemplate).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                any(),
                eq(Void.class)
        );
    }
    @Test
    void createVoteUpdatesExistingVoteInsteadOfCreatingNewOne() {
        UUID sessionId = UUID.randomUUID();
        Movie movie = Movie.builder().id(10L).title("The Matrix").cacheAt(ZonedDateTime.now()).build();
        Vote existing = Vote.builder()
                .sessionId(sessionId).participantId("participant-1")
                .movie(movie).decision(VoteDecision.DISLIKE)
                .createdAt(ZonedDateTime.now().minusMinutes(1)).build();

        when(movieRepository.findById(10L)).thenReturn(Optional.of(movie));
        when(voteRepository.findBySessionIdAndParticipantIdAndMovie_Id(sessionId, "participant-1", 10L))
                .thenReturn(Optional.of(existing));
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        Vote result = votingService.createVote(sessionId, "participant-1", 10L, VoteDecision.LIKE);

        assertThat(result).isSameAs(existing);
        assertThat(result.getDecision()).isEqualTo(VoteDecision.LIKE);
    }

    @Test
    void castVoteWaitsUntilEveryParticipantHasVoted() {
        UUID sessionId = UUID.randomUUID();
        Movie movie = movie(10L);
        mockParticipants(sessionId);
        when(movieRepository.findById(10L)).thenReturn(Optional.of(movie));
        when(voteRepository.save(any(Vote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(voteRepository.countParticipantsVoted(sessionId, 10L)).thenReturn(1L);

        var outcome = votingService.castVote(sessionId, "participant-1", 10L, VoteDecision.DISLIKE);

        assertThat(outcome.status()).isEqualTo(com.moviechoice.voting.dto.VoteOutcome.Status.WAITING);
        assertThat(outcome.voted()).isEqualTo(1);
        assertThat(outcome.total()).isEqualTo(2);
        verify(voteRepository, never()).deleteBySessionIdAndMovie_Id(sessionId, 10L);
        verify(restTemplate, never()).postForObject(any(String.class), any(), eq(Void.class));
    }

    @Test
    void castVoteAdvancesToNextMovieAfterAnyDislike() {
        UUID sessionId = UUID.randomUUID();
        Movie movie = movie(10L);
        mockParticipants(sessionId);
        when(movieRepository.findById(10L)).thenReturn(Optional.of(movie));
        when(voteRepository.save(any(Vote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(voteRepository.countParticipantsVoted(sessionId, 10L)).thenReturn(2L);
        when(voteRepository.countDistinctParticipants(sessionId, 10L, VoteDecision.LIKE)).thenReturn(1L);
        when(movieRepository.findAllIdsOrdered()).thenReturn(List.of(10L, 20L));
        when(catalogService.ensureHasMovieAt(1)).thenReturn(true);

        var outcome = votingService.castVote(sessionId, "participant-2", 10L, VoteDecision.DISLIKE);

        assertThat(outcome.status()).isEqualTo(com.moviechoice.voting.dto.VoteOutcome.Status.NEXT);
        assertThat(outcome.nextMovieIndex()).isEqualTo(1);
        verify(voteRepository).deleteBySessionIdAndMovie_Id(sessionId, 10L);
        verify(restTemplate).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                any(),
                eq(Void.class));
    }

    @Test
    void castVoteMatchesOnlyWhenEveryParticipantLikesMovie() {
        UUID sessionId = UUID.randomUUID();
        Movie movie = movie(10L);
        mockParticipants(sessionId);
        when(movieRepository.findById(10L)).thenReturn(Optional.of(movie));
        when(voteRepository.save(any(Vote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(voteRepository.countParticipantsVoted(sessionId, 10L)).thenReturn(2L);
        when(voteRepository.countDistinctParticipants(sessionId, 10L, VoteDecision.LIKE)).thenReturn(2L);

        var outcome = votingService.castVote(sessionId, "participant-2", 10L, VoteDecision.LIKE);

        assertThat(outcome.status()).isEqualTo(com.moviechoice.voting.dto.VoteOutcome.Status.MATCH);
        verify(voteRepository).deleteBySessionIdAndMovie_Id(sessionId, 10L);
        verify(restTemplate, never()).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                any(),
                eq(Void.class));
        verify(restTemplate).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/finish"),
                any(),
                eq(Void.class));
    }

    private void mockParticipants(UUID sessionId) {
        String url = "http://session-service/api/sessions/" + sessionId + "/participants";
        Map<String, Object> response = Map.of(
                "participants", List.of(
                        Map.of("id", "participant-1"),
                        Map.of("id", "participant-2")),
                "currentMovieIndex", 0,
                "votingStarted", true);
        when(restTemplate.getForObject(eq(url), eq(Map.class))).thenReturn(response);
        when(movieRepository.findAllIdsOrdered()).thenReturn(List.of(10L, 20L));
    }

    @Test
    void castVoteRejectsVoteForMovieThatIsNoLongerCurrent() {
        UUID sessionId = UUID.randomUUID();
        Movie stale = movie(10L);
        mockParticipants(sessionId);
        when(movieRepository.findById(10L)).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> votingService.castVote(sessionId, "participant-1", 99L, VoteDecision.LIKE))
                .isInstanceOf(VotingService.StaleVoteException.class)
                .hasMessageContaining("фильм уже сменился");
    }

    @Test
    void castVoteRejectsSessionWithSingleParticipant() {
        UUID sessionId = UUID.randomUUID();
        String url = "http://session-service/api/sessions/" + sessionId + "/participants";
        when(restTemplate.getForObject(eq(url), eq(Map.class))).thenReturn(Map.of(
                "participants", List.of(Map.of("id", "participant-1")),
                "currentMovieIndex", 0,
                "votingStarted", true));

        assertThatThrownBy(() -> votingService.castVote(sessionId, "participant-1", 10L, VoteDecision.LIKE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("минимум 2 участника");
    }

    @Test
    void getMoviesForVoteExposesGenreAndRatingForFrontendChips() {
        Movie cachedMovie = Movie.builder()
                .id(7L)
                .title("Inception")
                .overview("Dreams")
                .posterPath("/poster.jpg")
                .voteAvg(8.7)
                .genre("Фантастика, Триллер")
                .cacheAt(ZonedDateTime.now())
                .build();

        when(movieRepository.count()).thenReturn(1L);
        when(movieRepository.findAll(any(Sort.class))).thenReturn(List.of(cachedMovie));

        List<MovieResponse> movies = votingService.getMoviesForVote();

        assertThat(movies).singleElement().satisfies(movie -> {
            assertThat(movie.genre()).isEqualTo("Фантастика, Триллер");
            assertThat(movie.rating()).isEqualTo(8.7);
            assertThat(movie.posterPath()).isEqualTo("/poster.jpg");
        });
    }

    private static Movie movie(Long id) {
        return Movie.builder().id(id).title("Movie " + id).cacheAt(ZonedDateTime.now()).build();
    }

    private static TmdbMovieDto movieDto(Long id, String title, String overview, Double voteAverage, String posterPath) {
        TmdbMovieDto dto = new TmdbMovieDto();
        dto.setId(id);
        dto.setTitle(title);
        dto.setOverview(overview);
        dto.setVoteAverage(voteAverage);
        dto.setPosterPath(posterPath);
        return dto;
    }
}
