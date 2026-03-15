package com.ai.spring_lens.service.strategy;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("paragraph")
public class ParagraphPdfReaderStrategy implements PdfReaderStrategy {
    @Override
    public List<Document> read(Resource pdfResource) {
        return new ParagraphPdfDocumentReader(pdfResource).get();
    }
}