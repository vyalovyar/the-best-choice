package com.moviechoice.voting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moviechoice.voting.client.TmdbClient;
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
import org.springframework.test.util.ReflectionTestUtils;
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

    @InjectMocks
    private VotingService votingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(votingService, "sessionServiceUrl", "http://session-service");
    }

    //Тест для проверкает, что метод getMoviesForVote обновляет кэш фильмов, когда репозиторий пустой. Он имитирует ответ от TMDB клиента и проверяет, что фильм сохраняется в репозитории с правильными данными
    @Test
    void getMoviesForVoteRefreshesCacheWhenRepositoryIsEmpty() {
        TmdbMovieDto dto = movieDto(101L, "Interstellar", "Space epic", 8.7, "/poster.jpg");
        TmdbMoviesResponseDto response = new TmdbMoviesResponseDto();
        response.setResults(List.of(dto));
        List<Movie> expectedMovies = List.of(Movie.builder().id(101L).title("Interstellar").build());

        when(movieRepository.count()).thenReturn(0L);
        when(tmdbClient.getPopularMovies()).thenReturn(response);
        when(movieRepository.findById(101L)).thenReturn(Optional.empty());
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(movieRepository.findAll()).thenReturn(expectedMovies);

        List<Movie> actualMovies = votingService.getMoviesForVote();

        ArgumentCaptor<Movie> movieCaptor = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository).save(movieCaptor.capture());

        Movie savedMovie = movieCaptor.getValue();
        assertThat(savedMovie.getId()).isEqualTo(101L);
        assertThat(savedMovie.getTitle()).isEqualTo("Interstellar");
        assertThat(savedMovie.getOverview()).isEqualTo("Space epic");
        assertThat(savedMovie.getVoteAvg()).isEqualTo(8.7);
        assertThat(savedMovie.getPosterPath()).isEqualTo("/poster.jpg");
        assertThat(savedMovie.getCacheAt()).isNotNull();
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
        when(movieRepository.existsByOverviewIsNull()).thenReturn(false);
        when(movieRepository.findTopByOrderByCacheAtDesc()).thenReturn(Optional.of(cachedMovie));
        when(movieRepository.findAll()).thenReturn(List.of(cachedMovie));

        List<Movie> movies = votingService.getMoviesForVote();

        assertThat(movies).containsExactly(cachedMovie);
        verify(tmdbClient, never()).getPopularMovies();
        verify(movieRepository, never()).save(any(Movie.class));
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

        when(tmdbClient.getPopularMovies()).thenReturn(response);
        when(movieRepository.findById(42L)).thenReturn(Optional.of(existingMovie));
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> invocation.getArgument(0));

        votingService.updateMoviesFromTmdb();

        ArgumentCaptor<Movie> movieCaptor = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository).save(movieCaptor.capture());

        Movie savedMovie = movieCaptor.getValue();
        assertThat(savedMovie).isSameAs(existingMovie);
        assertThat(savedMovie.getTitle()).isEqualTo("New title");
        assertThat(savedMovie.getOverview()).isEqualTo("New overview");
        assertThat(savedMovie.getVoteAvg()).isEqualTo(8.4);
        assertThat(savedMovie.getPosterPath()).isEqualTo("/new.jpg");
        assertThat(savedMovie.getCacheAt()).isNotNull();
    }
    //тест для проверки, что метод updateMoviesFromTmdb останавливается, если TMDB клиент возвращает null. Он имитирует такой ответ и проверяет, что репозиторий фильмов не сохраняет никаких фильмов
    @Test
    void updateMoviesFromTmdbStopsWhenClientReturnsNull() {
        when(tmdbClient.getPopularMovies()).thenReturn(null);

        votingService.updateMoviesFromTmdb();

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
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                bodyCaptor.capture(),
                eq(Void.class)
        );
        assertThat(bodyCaptor.getValue()).containsEntry("currentMovieIndex", 3);
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
