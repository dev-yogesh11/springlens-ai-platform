package com.ai.spring_lens.service;

import com.ai.spring_lens.config.IngestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;
    private final IngestionProperties properties;

    public DocumentIngestionService(VectorStore vectorStore,
                                    IngestionProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.getChunkSize())
                .withMinChunkSizeChars(properties.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(properties.getMinChunkLengthToEmbed())
                .withMaxNumChunks(properties.getMaxNumChunks())
                .withKeepSeparator(properties.isKeepSeparator())
                .build();
    }

    public IngestionResult ingest(Resource pdfResource, String originalFileName) {
        log.info("Starting ingestion for file={}", originalFileName);

        if (isDuplicate(originalFileName)) {
            log.warn("File already ingested, skipping file={}", originalFileName);
            return IngestionResult.duplicate(originalFileName);
        }

        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);
        List<Document> documents = splitter.apply(reader.get());

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        documents.forEach(doc -> {
            doc.getMetadata().put("original_file_name", originalFileName);
            doc.getMetadata().put("ingestion_timestamp", timestamp);
        });

        vectorStore.add(documents);

        log.info("Ingestion complete file={} chunks={}",
                originalFileName, documents.size());

        return IngestionResult.success(originalFileName, documents.size());
    }

    private boolean isDuplicate(String originalFileName) {
        try {
            List<Document> existing = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(originalFileName)
                            .topK(1)
                            .filterExpression(
                                    "original_file_name == '" + originalFileName + "'"
                            )
                            .build()
            );
            return !existing.isEmpty();
        } catch (Exception e) {
            log.warn("Duplicate check failed, proceeding: {}", e.getMessage());
            return false;
        }
    }

    public record IngestionResult(
            String fileName,
            int chunks,
            String status
    ) {
        public static IngestionResult success(String fileName, int chunks) {
            return new IngestionResult(fileName, chunks, "ingested");
        }

        public static IngestionResult duplicate(String fileName) {
            return new IngestionResult(fileName, 0, "already_exists");
        }
    }
}