package com.moviechoice.voting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviechoice.voting.dto.VoteRequest;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.entity.VoteDecision;
import com.moviechoice.voting.repository.MovieRepository;
import com.moviechoice.voting.repository.VoteRepository;
import com.moviechoice.voting.service.CatalogService;
import com.moviechoice.voting.service.MovieCacheService;
import com.moviechoice.voting.service.VotingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class VotingRoundFlowTest {

    private static final Long MOVIE_ID = 42L;
    private static final String PARTICIPANT_ONE = "participant-1";
    private static final String PARTICIPANT_TWO = "participant-2";

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private VoteRepository voteRepository;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private MovieCacheService movieCacheService;

    @Mock
    private CatalogService catalogService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<Vote> storedVotes = new ArrayList<>();
    private UUID sessionId;
    private VotingController votingController;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
        VotingService votingService = new VotingService(movieRepository, voteRepository, restTemplate, movieCacheService, catalogService);
        ReflectionTestUtils.setField(votingService, "sessionServiceUrl", "http://session-service");
        votingController = new VotingController(votingService, messagingTemplate, objectMapper);

        when(restTemplate.getForObject(
                "http://session-service/api/sessions/" + sessionId + "/participants",
                Map.class)).thenReturn(Map.of(
                "participants", List.of(
                        Map.of("id", PARTICIPANT_ONE),
                        Map.of("id", PARTICIPANT_TWO)),
                "currentMovieIndex", 0,
                "votingStarted", true));
        lenient().when(movieRepository.findAllIdsOrdered()).thenReturn(List.of(MOVIE_ID, 43L));
        lenient().when(catalogService.ensureHasMovieAt(anyInt())).thenReturn(true);

        Movie movie = Movie.builder()
                .id(MOVIE_ID)
                .title("Test Movie")
                .posterPath("/poster.jpg")
                .build();
        when(movieRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movie));
        when(voteRepository.findBySessionIdAndParticipantIdAndMovie_Id(
                any(UUID.class), anyString(), anyLong()))
                .thenAnswer(invocation -> storedVotes.stream()
                        .filter(vote -> vote.getSessionId().equals(invocation.getArgument(0))
                                && vote.getParticipantId().equals(invocation.getArgument(1))
                                && vote.getMovie().getId().equals(invocation.getArgument(2)))
                        .findFirst());
        when(voteRepository.save(any(Vote.class))).thenAnswer(invocation -> {
            Vote vote = invocation.getArgument(0);
            storedVotes.removeIf(saved -> saved.getSessionId().equals(vote.getSessionId())
                    && saved.getParticipantId().equals(vote.getParticipantId())
                    && saved.getMovie().getId().equals(vote.getMovie().getId()));
            vote.setId(UUID.randomUUID());
            storedVotes.add(vote);
            return vote;
        });
        when(voteRepository.countParticipantsVoted(any(UUID.class), anyLong())).thenAnswer(invocation ->
                storedVotes.stream()
                        .filter(vote -> vote.getSessionId().equals(invocation.getArgument(0))
                                && vote.getMovie().getId().equals(invocation.getArgument(1)))
                        .map(Vote::getParticipantId)
                        .distinct()
                        .count());
        when(voteRepository.countDistinctParticipants(any(UUID.class), anyLong(), any(VoteDecision.class)))
                .thenAnswer(invocation -> storedVotes.stream()
                        .filter(vote -> vote.getSessionId().equals(invocation.getArgument(0))
                                && vote.getMovie().getId().equals(invocation.getArgument(1))
                                && vote.getDecision() == invocation.getArgument(2))
                        .map(Vote::getParticipantId)
                        .distinct()
                        .count());
        doAnswer(invocation -> {
            storedVotes.removeIf(vote -> vote.getSessionId().equals(invocation.getArgument(0))
                    && vote.getMovie().getId().equals(invocation.getArgument(1)));
            return null;
        }).when(voteRepository).deleteBySessionIdAndMovie_Id(any(UUID.class), anyLong());
    }

    @Test
    void twoDislikesAdvanceRoundAndPublishProgressAndNextMovie() throws Exception {
        String votesTopic = topic("/votes");
        String indexTopic = topic("/index");
        String matchTopic = topic("/match");
        when(movieRepository.findAllIdsOrdered()).thenReturn(List.of(MOVIE_ID, 99L));

        votingController.handlerVote(request(PARTICIPANT_ONE, VoteDecision.DISLIKE));

        ArgumentCaptor<Object> progressCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(votesTopic), progressCaptor.capture());
        JsonNode firstProgress = objectMapper.readTree((String) progressCaptor.getValue());
        assertThat(firstProgress.get("votedCount").asInt()).isEqualTo(1);
        assertThat(firstProgress.get("totalParticipants").asInt()).isEqualTo(2);
        verify(messagingTemplate, never()).convertAndSend(eq(indexTopic), any(Object.class));
        verify(messagingTemplate, never()).convertAndSend(eq(matchTopic), any(Object.class));

        votingController.handlerVote(request(PARTICIPANT_TWO, VoteDecision.DISLIKE));

        ArgumentCaptor<Object> indexCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(votesTopic), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(indexTopic), indexCaptor.capture());
        JsonNode indexUpdate = objectMapper.readTree((String) indexCaptor.getValue());
        assertThat(indexUpdate.get("movieIndex").asInt()).isEqualTo(1);
        ArgumentCaptor<Object> allProgressCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(votesTopic), allProgressCaptor.capture());
        JsonNode secondProgress = objectMapper.readTree((String) allProgressCaptor.getAllValues().get(1));
        assertThat(secondProgress.get("votedCount").asInt()).isEqualTo(2);
        assertThat(secondProgress.get("totalParticipants").asInt()).isEqualTo(2);
        verify(messagingTemplate, never()).convertAndSend(eq(matchTopic), any(Object.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> indexBodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                indexBodyCaptor.capture(),
                eq(Void.class));
        assertThat(indexBodyCaptor.getValue().getBody()).isEqualTo(Map.of("currentMovieIndex", 1));
        assertThat(storedVotes).isEmpty();
    }

    @Test
    void twoLikesPublishMatchInsteadOfAdvancingRound() {
        votingController.handlerVote(request(PARTICIPANT_ONE, VoteDecision.LIKE));
        votingController.handlerVote(request(PARTICIPANT_TWO, VoteDecision.LIKE));

        verify(messagingTemplate).convertAndSend(eq(topic("/match")), any(Object.class));
        verify(messagingTemplate, never()).convertAndSend(eq(topic("/index")), any(Object.class));
        verify(restTemplate).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/finish"),
                any(),
                eq(Void.class));
        verify(restTemplate, never()).postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                any(),
                eq(Void.class));
        assertThat(storedVotes).isEmpty();
    }

    @Test
    void staleVoteForPreviousMovieNotifiesOnlyThatParticipant() throws Exception {
        when(movieRepository.findAllIdsOrdered()).thenReturn(List.of(MOVIE_ID, 99L));

        votingController.handlerVote(request(PARTICIPANT_ONE, VoteDecision.LIKE));
        votingController.handlerVote(request(PARTICIPANT_TWO, VoteDecision.LIKE));

        VoteRequest stale = request(PARTICIPANT_ONE, VoteDecision.DISLIKE);
        stale.setMovieId(99L);

        ArgumentCaptor<Object> errorCaptor = ArgumentCaptor.forClass(Object.class);
        votingController.handlerVote(stale);

        verify(messagingTemplate).convertAndSend(eq(topic("/errors")), errorCaptor.capture());
        JsonNode error = objectMapper.readTree((String) errorCaptor.getValue());
        assertThat(error.get("participantId").asText()).isEqualTo(PARTICIPANT_ONE);
        assertThat(error.get("message").asText()).contains("фильм уже сменился");
    }

    @Test
    void roundDoesNotAdvanceWhenSessionServiceRejectsIndexUpdate() {
        when(movieRepository.findAllIdsOrdered()).thenReturn(List.of(MOVIE_ID, 99L));
        when(restTemplate.postForObject(
                eq("http://session-service/api/sessions/" + sessionId + "/update-index"),
                any(),
                eq(Void.class)))
                .thenThrow(new RuntimeException("session service unavailable"));

        ArgumentCaptor<Object> errorCaptor = ArgumentCaptor.forClass(Object.class);
        votingController.handlerVote(request(PARTICIPANT_ONE, VoteDecision.DISLIKE));
        votingController.handlerVote(request(PARTICIPANT_TWO, VoteDecision.DISLIKE));

        verify(messagingTemplate, never()).convertAndSend(eq(topic("/index")), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(topic("/errors")), errorCaptor.capture());
        assertThat(errorCaptor.getValue().toString()).contains("Не удалось переключить фильм");
    }

    private VoteRequest request(String participantId, VoteDecision decision) {
        VoteRequest request = new VoteRequest();
        request.setSessionId(sessionId);
        request.setParticipantId(participantId);
        request.setMovieId(MOVIE_ID);
        request.setDecision(decision);
        return request;
    }

    private String topic(String suffix) {
        return "/topic/session/" + sessionId + suffix;
    }
}
