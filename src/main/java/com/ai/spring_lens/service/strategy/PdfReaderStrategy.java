package com.ai.spring_lens.service.strategy;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import java.util.List;

public interface PdfReaderStrategy {
    List<Document> read(Resource pdfResource);
}