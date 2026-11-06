package self.research.ontology.migrator.support;

import self.research.ontology.migrator.core.BlobFile;
import self.research.ontology.migrator.core.BlobSource;
import self.research.ontology.migrator.core.CloseableIterator;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class MemoryBlobSource implements BlobSource {

    private final Map<String, List<BlobFile>> files = new HashMap<>();
    private final Map<String, Function<BlobFile, InputStream>> content = new HashMap<>();

    public MemoryBlobSource add(BlobFile file, byte[] bytes) {
        return add(file, f -> new ByteArrayInputStream(bytes));
    }

    public MemoryBlobSource add(BlobFile file, Function<BlobFile, InputStream> stream) {
        files.computeIfAbsent(file.bucket(), k -> new ArrayList<>()).add(file);
        content.put(file.bucket() + "/" + file.id(), stream);
        return this;
    }

    @Override
    public boolean exists(String bucket) {
        return files.containsKey(bucket);
    }

    @Override
    public CloseableIterator<BlobFile> list(String bucket) {
        return CloseableIterator.of(new ArrayList<>(files.getOrDefault(bucket, List.of())));
    }

    @Override
    public InputStream open(BlobFile file) {
        return content.get(file.bucket() + "/" + file.id()).apply(file);
    }

    @Override
    public List<BlobFile> sample(String bucket, int size) {
        List<BlobFile> all = files.getOrDefault(bucket, List.of());
        return all.size() <= size ? all : all.subList(0, size);
    }
}
