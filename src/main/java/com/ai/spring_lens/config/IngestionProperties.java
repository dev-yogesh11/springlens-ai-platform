package com.ai.spring_lens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "springlens.ingestion")
public class IngestionProperties {
    private int chunkSize = 512;
    private int minChunkSizeChars = 50;
    private int minChunkLengthToEmbed = 5;
    private int maxNumChunks = 10000;
    private boolean keepSeparator = true;
    private double similarityThreshold = 0.7;
    private int topK = 4;
    private String pdfReaderType="auto";
}