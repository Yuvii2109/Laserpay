package com.laserpay.pdei.docproc.config;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.docproc.extract.DocumentExtractor;
import com.laserpay.pdei.docproc.extract.EmlExtractor;
import com.laserpay.pdei.docproc.extract.ExtractorRegistry;
import com.laserpay.pdei.docproc.extract.PdfBoxDocumentExtractor;
import com.laserpay.pdei.docproc.extract.PlainTextExtractor;
import com.laserpay.pdei.docproc.extract.TikaDocumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the extraction pipeline.
 *
 * <p>Registry order is the contract (see {@link ExtractorRegistry}): the two structure-aware
 * readers first, then the fast path for text, then Tika as the catch-all. Changing this order
 * changes which parser sees a document, so it lives in one obvious place rather than being
 * implied by bean names or {@code @Order} annotations scattered across five classes.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocProcProperties.class)
public class DocProcConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocProcConfiguration.class);

    @Bean
    public Clocks docprocClocks() {
        return Clocks.system();
    }

    @Bean
    public PdfBoxDocumentExtractor pdfBoxDocumentExtractor(Clocks clock, DocProcProperties properties) {
        return new PdfBoxDocumentExtractor(clock, properties.getMaxPdfPages());
    }

    @Bean
    public EmlExtractor emlExtractor(Clocks clock) {
        return new EmlExtractor(clock);
    }

    @Bean
    public PlainTextExtractor plainTextExtractor(Clocks clock) {
        return new PlainTextExtractor(clock);
    }

    @Bean
    public TikaDocumentExtractor tikaDocumentExtractor(Clocks clock, DocProcProperties properties) {
        return new TikaDocumentExtractor(clock, properties.getTikaWriteLimitChars());
    }

    /**
     * Selection order, most specific first. Tika is last because it claims everything.
     */
    @Bean
    public ExtractorRegistry extractorRegistry(PdfBoxDocumentExtractor pdf,
                                               EmlExtractor eml,
                                               PlainTextExtractor text,
                                               TikaDocumentExtractor tika) {
        List<DocumentExtractor> ordered = List.of(pdf, eml, text, tika);
        log.info("document extractors registered in selection order: {}",
                ordered.stream().map(DocumentExtractor::name).toList());
        return new ExtractorRegistry(ordered);
    }

    /**
     * Bounded pool that exists purely so an extraction can be abandoned on timeout.
     *
     * <p>A hung parser must not hold a Kafka consumer thread forever: the container would stop
     * polling, the group would rebalance, and the whole partition would stall behind one bad
     * PDF. Running the parse on this pool lets the caller give up, quarantine the artifact and
     * commit the offset.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService extractionExecutor(DocProcProperties properties) {
        int threads = Math.max(1, properties.getExtractorThreads());
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "docproc-extract-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }
}
