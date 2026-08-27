package com.laserpay.pdei.docproc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * document-processor-service (platform contract 2: port 8086, worker + web).
 *
 * <p>Turns evidence artifacts into searchable, verifiable text: consumes EVIDENCE events, reads
 * the object from MinIO, extracts text and metadata with Tika / PDFBox / the .eml reader,
 * verifies the sha256, writes {@code evidence.extracted_text} (which the V10 trigger turns into
 * the FTS {@code search_vector}) and publishes an EVIDENCE event so readiness recomputes.
 *
 * <p>The persistence and domain layers arrive by autoconfiguration:
 * {@code platform-persistence} registers the entity and repository scan,
 * {@code evidence-core} registers the {@code ObjectStore} and {@code EventPublisherPort}. No
 * component scan beyond this package is needed or wanted.
 */
@SpringBootApplication
public class DocumentProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentProcessorApplication.class, args);
    }
}
