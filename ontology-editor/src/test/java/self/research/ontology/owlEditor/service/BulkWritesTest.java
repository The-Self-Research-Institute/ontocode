package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkWritesTest {

    @SuppressWarnings("unchecked")
    private final MongoRepository<String, String> repository = mock(MongoRepository.class);
    private final MongoOperations mongo = mock(MongoOperations.class);

    private static List<String> documents(int count) {
        return IntStream.range(0, count).mapToObj(i -> "d" + i).toList();
    }

    @Test
    void aListWithinTheLimitIsSavedInOneCall() {
        List<String> docs = documents(BulkWrites.MAX_DOCUMENTS_PER_WRITE);
        when(repository.saveAll(docs)).thenReturn(docs);

        List<String> saved = BulkWrites.saveAll(repository, docs);

        assertEquals(docs, saved);
        verify(repository, times(1)).saveAll(anyIterable());
    }

    @Test
    void aLargeListIsSavedInPiecesThatNeverExceedTheLimitAndKeepsEveryDocumentInOrder() {
        List<String> docs = documents(25_001);
        when(repository.saveAll(anyIterable())).thenAnswer(call -> new ArrayList<>((List<String>) call.getArgument(0)));

        List<String> saved = BulkWrites.saveAll(repository, docs);

        assertEquals(docs, saved);
        ArgumentCaptor<Iterable<String>> pieces = ArgumentCaptor.forClass(Iterable.class);
        verify(repository, times(3)).saveAll(pieces.capture());
        for (Iterable<String> piece : pieces.getAllValues()) {
            assertTrue(((List<String>) piece).size() <= BulkWrites.MAX_DOCUMENTS_PER_WRITE);
        }
    }

    @Test
    void anEmptyListStillSavesNothingTwice() {
        when(repository.saveAll(anyIterable())).thenReturn(List.of());

        assertEquals(List.of(), BulkWrites.saveAll(repository, List.of()));
        verify(repository, times(1)).saveAll(anyIterable());
    }

    @Test
    void insertSplitsALargeListIntoPiecesForAClassAndForACollection() {
        List<String> docs = documents(25_001);

        BulkWrites.insert(mongo, docs, String.class);
        BulkWrites.insert(mongo, docs, "parked");

        ArgumentCaptor<List<String>> byClass = ArgumentCaptor.forClass(List.class);
        verify(mongo, times(3)).insert(byClass.capture(), eq(String.class));
        assertEquals(25_001, byClass.getAllValues().stream().mapToInt(List::size).sum());
        assertTrue(byClass.getAllValues().stream().allMatch(p -> p.size() <= BulkWrites.MAX_DOCUMENTS_PER_WRITE));
        verify(mongo, times(3)).insert(anyCollection(), eq("parked"));
    }

    @Test
    void insertOfAnEmptyListWritesNothing() {
        BulkWrites.insert(mongo, List.of(), String.class);
        BulkWrites.insert(mongo, List.of(), "parked");

        verify(mongo, never()).insert(anyCollection(), any(Class.class));
        verify(mongo, never()).insert(anyCollection(), any(String.class));
    }
}
